"""Gateway — the entry point. Wires interceptors around a provider adapter."""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import AsyncIterator
from typing import Any

from .context import InterceptorContext
from .exceptions import ConfigurationError
from .interceptors.audit import AuditInterceptor
from .interceptors.base import Interceptor
from .interceptors.chain import Executor, InterceptorChain
from .interceptors.reliability.policy import ExecutorPolicy
from .interceptors.reliability.stream_buffer import StreamBuffer
from .pricing import PricingProvider
from .providers import ProviderAdapter, build_adapter
from .providers.mock import MockProvider
from .request import GavioRequest
from .response import GavioResponse
from .types import Message, PromptLineage, Provider

logger = logging.getLogger("gavio.gateway")


class Gateway:
    """Routes a request through the interceptor pipeline to a provider.

    Build via :meth:`Gateway.builder`. A single instance is safe to share
    across threads and async tasks — per-request state lives in an
    :class:`InterceptorContext` created fresh for every call.
    """

    def __init__(
        self,
        adapter: ProviderAdapter,
        model: str,
        interceptors: list[Interceptor],
        *,
        dry_run: bool = False,
    ) -> None:
        self._adapter = adapter
        self._model = model
        self._dry_run = dry_run
        # Separate plain pre/post interceptors from executor-wrapping policies.
        self._policies: list[ExecutorPolicy] = [
            i for i in interceptors if isinstance(i, ExecutorPolicy)
        ]
        regular = [i for i in interceptors if not isinstance(i, ExecutorPolicy)]
        self._chain = InterceptorChain(regular)

    @staticmethod
    def builder() -> GatewayBuilder:
        return GatewayBuilder()

    @classmethod
    def from_config(cls, config: str | dict) -> Gateway:
        """Build a Gateway from a config dict or a JSON/YAML file path (F-DX-05)."""
        from .config import build_from_config, load_config

        data = config if isinstance(config, dict) else load_config(config)
        return build_from_config(data)

    @property
    def model(self) -> str:
        return self._model

    @property
    def provider_name(self) -> str:
        return self._adapter.provider_name

    async def complete(
        self,
        messages: list[Message],
        *,
        model: str | None = None,
        agent_id: str | None = None,
        parent_trace_id: str | None = None,
        session_id: str | None = None,
        metadata: dict[str, Any] | None = None,
        lineage: PromptLineage | None = None,
        **options: Any,
    ) -> GavioResponse:
        request = GavioRequest(
            messages=messages,
            model=model or self._model,
            provider=Provider.coerce(self._adapter.provider_name),
            agent_id=agent_id,
            parent_trace_id=parent_trace_id,
            session_id=session_id,
            options=options,
            metadata=metadata or {},
            lineage=lineage,
        )
        ctx = InterceptorContext(
            trace_id=request.trace_id,
            agent_id=agent_id,
            parent_trace_id=parent_trace_id,
            session_id=session_id,
            dry_run=self._dry_run,
        )
        executor = self._build_executor(ctx)
        return await self._chain.execute(request, ctx, executor)

    async def stream(
        self,
        messages: list[Message],
        *,
        model: str | None = None,
        agent_id: str | None = None,
        parent_trace_id: str | None = None,
        session_id: str | None = None,
        metadata: dict[str, Any] | None = None,
        **options: Any,
    ) -> AsyncIterator[str]:
        """Stream a completion, buffering the provider stream (F-REL-06).

        The provider stream is buffered in full so the post-interceptor pipeline
        (guardrails, PII restore, audit) runs on the complete response before any
        chunk reaches the caller. Pre/post interceptors run via the chain;
        executor policies (retry, circuit breaker, cache) are not applied to the
        streaming path.
        """
        request = GavioRequest(
            messages=messages,
            model=model or self._model,
            provider=Provider.coerce(self._adapter.provider_name),
            agent_id=agent_id,
            parent_trace_id=parent_trace_id,
            session_id=session_id,
            options=options,
            metadata=metadata or {},
        )
        ctx = InterceptorContext(
            trace_id=request.trace_id,
            agent_id=agent_id,
            parent_trace_id=parent_trace_id,
            session_id=session_id,
            dry_run=self._dry_run,
        )
        started = time.monotonic()
        buffer = StreamBuffer()

        async def buffering_executor(req: GavioRequest) -> GavioResponse:
            async for chunk in self._adapter.stream(req):
                buffer.append(chunk)
            return self._adapter.build_stream_response(req, buffer.text(), started)

        response = await self._chain.execute(request, ctx, buffering_executor)
        # Post-interceptors have run on the fully buffered response; emit it now.
        yield response.content

    def complete_sync(
        self, messages: list[Message], **kwargs: Any
    ) -> GavioResponse:
        """Synchronous wrapper for non-async callers (scripts, Django views)."""
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self.complete(messages, **kwargs))
        raise RuntimeError(
            "complete_sync() cannot be called from within a running event loop; "
            "use 'await gateway.complete(...)' instead"
        )

    async def health_check(self) -> bool:
        return await self._adapter.health_check()

    def _build_executor(self, ctx: InterceptorContext) -> Executor:
        async def base(request: GavioRequest) -> GavioResponse:
            return await self._adapter.complete(request)

        executor: Executor = base
        # Wrap so the first-registered policy ends up outermost.
        for policy in reversed(self._policies):
            executor = self._wrap_policy(policy, executor, ctx)
        return executor

    @staticmethod
    def _wrap_policy(
        policy: ExecutorPolicy, inner: Executor, ctx: InterceptorContext
    ) -> Executor:
        async def wrapped(request: GavioRequest) -> GavioResponse:
            if ctx.dry_run and not policy.dry_run_safe:
                return await inner(request)
            return await policy.around(request, ctx, inner)

        return wrapped


class GatewayBuilder:
    """Fluent builder for :class:`Gateway`."""

    def __init__(self) -> None:
        self._provider: Provider | None = None
        self._model: str | None = None
        self._adapter: ProviderAdapter | None = None
        self._interceptors: list[Interceptor] = []
        self._dev_mode = False
        self._dry_run = False
        self._pricing = PricingProvider()

    def provider(self, provider: Provider | str) -> GatewayBuilder:
        self._provider = Provider.coerce(provider)
        return self

    def model(self, model: str) -> GatewayBuilder:
        self._model = model
        return self

    def adapter(self, adapter: ProviderAdapter) -> GatewayBuilder:
        self._adapter = adapter
        return self

    def use(self, interceptor: Interceptor) -> GatewayBuilder:
        self._interceptors.append(interceptor)
        return self

    def pricing(self, pricing: PricingProvider) -> GatewayBuilder:
        self._pricing = pricing
        return self

    def dev_mode(self, enabled: bool = True) -> GatewayBuilder:
        self._dev_mode = enabled
        return self

    def dry_run(self, enabled: bool = True) -> GatewayBuilder:
        self._dry_run = enabled
        return self

    def build(self) -> Gateway:
        adapter = self._resolve_adapter()
        model = self._model or _default_model(adapter)
        interceptors = list(self._interceptors)

        # Dev mode auto-wires a stdout audit sink if none was added.
        if self._dev_mode and not any(
            isinstance(i, AuditInterceptor) for i in interceptors
        ):
            interceptors.insert(0, AuditInterceptor())

        return Gateway(adapter, model, interceptors, dry_run=self._dry_run)

    def _resolve_adapter(self) -> ProviderAdapter:
        if self._adapter is not None:
            return self._adapter
        if self._dev_mode:
            return MockProvider(pricing=self._pricing)
        if self._provider is None:
            raise ConfigurationError(
                "No provider configured. Call .provider(...), .adapter(...), "
                "or .dev_mode(True)."
            )
        return build_adapter(self._provider, pricing=self._pricing)


def _default_model(adapter: ProviderAdapter) -> str:
    return {
        "openai": "gpt-4o",
        "anthropic": "claude-sonnet-4-6",
        "mock": "mock",
    }.get(adapter.provider_name, "mock")
