"""Gateway — the entry point. Wires interceptors around a provider adapter."""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import AsyncIterator
from dataclasses import replace
from typing import Any

from .context import InterceptorContext
from .exceptions import ConfigurationError
from .exporters.base import GavioRuntimeExporter
from .inspector import Inspector, InspectorConfig
from .inspector.emitter import TraceEmitter
from .inspector.inspector import compute_lints
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
        inspector: Inspector | None = None,
    ) -> None:
        self._adapter = adapter
        self._model = model
        self._dry_run = dry_run
        self._inspector = inspector
        if inspector is not None:
            # /api/replay re-fires through this gateway — full chain, never bypassed.
            inspector.replay_handler = self.complete
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

    @property
    def inspector(self) -> Inspector | None:
        """The Gavio Inspector, or None when inspection is disabled (default)."""
        return self._inspector

    async def complete(
        self,
        messages: list[Message],
        *,
        model: str | None = None,
        agent_id: str | None = None,
        parent_trace_id: str | None = None,
        session_id: str | None = None,
        metadata: dict[str, Any] | None = None,
        images: list[bytes] | None = None,
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
            images=images or [],
            lineage=lineage,
        )
        ctx = InterceptorContext.from_request(request, dry_run=self._dry_run)
        emitter = self._inspector.emitter(request) if self._inspector else None
        executor = self._build_executor(ctx, emitter)
        if emitter is None:
            return await self._chain.execute(request, ctx, executor)
        return await self._execute_traced(request, ctx, executor, emitter)

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
        ctx = InterceptorContext.from_request(request, dry_run=self._dry_run)
        started = time.monotonic()
        buffer = StreamBuffer()

        async def buffering_executor(req: GavioRequest) -> GavioResponse:
            async for chunk in self._adapter.stream(req):
                buffer.append(chunk)
            return self._adapter.build_stream_response(req, buffer.text(), started)

        emitter = self._inspector.emitter(request) if self._inspector else None
        if emitter is None:
            response = await self._chain.execute(request, ctx, buffering_executor)
        else:
            executor = emitter.wrap_provider_call(self._adapter.provider_name, buffering_executor)
            response = await self._execute_traced(request, ctx, executor, emitter)
        # Post-interceptors have run on the fully buffered response; emit it now.
        yield response.content

    async def embed(
        self,
        texts: list[str],
        *,
        model: str | None = None,
        agent_id: str | None = None,
        parent_trace_id: str | None = None,
        session_id: str | None = None,
        metadata: dict[str, Any] | None = None,
        **options: Any,
    ) -> GavioResponse:
        """Embed texts through the same interceptor pipeline as completions (F-SEC-10).

        Every input runs the full pre-interceptor chain — PII guard included —
        before the provider's embedding API is called, and the post chain
        (audit, metrics) runs on the way out. The response carries one vector
        per input in :attr:`GavioResponse.embeddings` and empty ``content``.
        """
        request = GavioRequest(
            messages=[{"role": "user", "content": text} for text in texts],
            model=model or self._model,
            provider=Provider.coerce(self._adapter.provider_name),
            agent_id=agent_id,
            parent_trace_id=parent_trace_id,
            session_id=session_id,
            options=options,
            metadata={**(metadata or {}), "call_type": "embedding"},
        )
        ctx = InterceptorContext.from_request(request, dry_run=self._dry_run)
        emitter = self._inspector.emitter(request) if self._inspector else None
        executor = self._build_executor(ctx, emitter, call=self._adapter.embed)
        if emitter is None:
            return await self._chain.execute(request, ctx, executor)
        return await self._execute_traced(request, ctx, executor, emitter)

    def complete_sync(self, messages: list[Message], **kwargs: Any) -> GavioResponse:
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

    def _build_executor(
        self,
        ctx: InterceptorContext,
        emitter: TraceEmitter | None = None,
        call: Executor | None = None,
    ) -> Executor:
        provider_call = call or self._adapter.complete

        async def base(request: GavioRequest) -> GavioResponse:
            return await provider_call(request)

        executor: Executor = base
        # provider.call.* events wrap the innermost call — one pair per attempt.
        if emitter is not None:
            executor = emitter.wrap_provider_call(self._adapter.provider_name, executor)
        # Wrap so the first-registered policy ends up outermost.
        for policy in reversed(self._policies):
            executor = self._wrap_policy(policy, executor, ctx)
        return executor

    async def _execute_traced(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        executor: Executor,
        emitter: TraceEmitter,
    ) -> GavioResponse:
        """Run the chain bracketed by trace.start / trace.error / trace.end."""
        emitter.trace_start(request)
        try:
            response = await self._chain.execute(request, ctx, executor, emitter=emitter)
        except Exception as error:  # noqa: BLE001 - re-raised unchanged
            emitter.trace_error(error)
            emitter.trace_end_error(ctx)
            raise
        emitter.trace_end(response, ctx)
        return response

    @staticmethod
    def _wrap_policy(policy: ExecutorPolicy, inner: Executor, ctx: InterceptorContext) -> Executor:
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
        self._inspect: InspectorConfig | None = None
        self._exporters: list[GavioRuntimeExporter] = []

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

    def inspect(self, value: bool | InspectorConfig = True) -> GatewayBuilder:
        """Enable the Gavio Inspector (F-DX-09) — off by default.

        Pass True for defaults or an :class:`~gavio.inspector.InspectorConfig`
        for explicit settings. Dev mode never enables this implicitly.
        """
        if isinstance(value, InspectorConfig):
            self._inspect = replace(value, enabled=True)
        else:
            self._inspect = InspectorConfig(enabled=True) if value else None
        return self

    def exporter(self, exporter: GavioRuntimeExporter) -> GatewayBuilder:
        """Subscribe a runtime event exporter.

        Exporters use the Inspector event stream but do not start the web UI by
        default. If inspection was otherwise disabled, adding an exporter enables
        metadata-mode events with ``start_server=False``.
        """
        self._exporters.append(exporter)
        return self

    def build(self) -> Gateway:
        adapter = self._resolve_adapter()
        model = self._model or _default_model(adapter)
        interceptors = list(self._interceptors)

        # Dev mode auto-wires a stdout audit sink if none was added.
        if self._dev_mode and not any(isinstance(i, AuditInterceptor) for i in interceptors):
            interceptors.insert(0, AuditInterceptor())

        inspector = self._build_inspector(adapter, model, interceptors)
        if inspector is not None:
            for exporter in self._exporters:
                inspector.bus.subscribe(exporter.export_event)
        return Gateway(adapter, model, interceptors, dry_run=self._dry_run, inspector=inspector)

    def _build_inspector(
        self,
        adapter: ProviderAdapter,
        model: str,
        interceptors: list[Interceptor],
    ) -> Inspector | None:
        config = self._resolve_inspector_config()
        if config is None and self._exporters:
            config = InspectorConfig(enabled=True, mode="metadata", start_server=False)
        if config is None or not config.enabled:
            return None
        config.validate(dev_mode=self._dev_mode)
        names = [i.name for i in interceptors]
        pipeline = {
            "provider": adapter.provider_name,
            "model": model,
            "devMode": self._dev_mode,
            "dryRun": self._dry_run,
            "interceptors": [{"name": name} for name in names],
            "lints": compute_lints(names),
        }
        inspector = Inspector(config, pipeline, dev_mode=self._dev_mode)
        inspector.pricing = self._pricing
        if config.start_server:
            inspector.start_server()
        return inspector

    def _resolve_inspector_config(self) -> InspectorConfig | None:
        """Merge the builder setting with GAVIO_INSPECT* environment variables."""
        config = self._inspect
        if config is None and os.environ.get("GAVIO_INSPECT", "").lower() in ("1", "true"):
            config = InspectorConfig(enabled=True)
        if config is None:
            return None
        if port := os.environ.get("GAVIO_INSPECT_PORT"):
            config = replace(config, port=int(port))
        if mode := os.environ.get("GAVIO_INSPECT_MODE"):
            config = replace(config, mode=mode)
        return config

    def _resolve_adapter(self) -> ProviderAdapter:
        if self._adapter is not None:
            return self._adapter
        if self._dev_mode:
            return MockProvider(pricing=self._pricing)
        if self._provider is None:
            raise ConfigurationError(
                "No provider configured. Call .provider(...), .adapter(...), or .dev_mode(True)."
            )
        return build_adapter(self._provider, pricing=self._pricing)


def _default_model(adapter: ProviderAdapter) -> str:
    return {
        "openai": "gpt-4o",
        "anthropic": "claude-sonnet-4-6",
        "openrouter": "openai/gpt-4o",
        "mock": "mock",
    }.get(adapter.provider_name, "mock")
