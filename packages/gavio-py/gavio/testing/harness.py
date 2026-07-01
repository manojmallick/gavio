"""GavioTestKit — run interceptor chains in isolation for unit tests.

Full version (F-DX-03) ships in v0.2.0; this v0.1.0 kit already lets you drive
a chain against a :class:`MockProvider` and assert on PII detection, the
redacted request, and the resulting audit record.
"""

from __future__ import annotations

from ..context import InterceptorContext
from ..interceptors.base import Interceptor
from ..interceptors.chain import Executor, InterceptorChain
from ..interceptors.reliability.policy import ExecutorPolicy
from ..providers.base import ProviderAdapter
from ..providers.mock import MockProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ..types import Message, Provider


class _CaptureInterceptor(Interceptor):
    """Records the request as it reaches the provider (post-redaction)."""

    def __init__(self) -> None:
        self.captured: GavioRequest | None = None

    @property
    def name(self) -> str:
        return "_capture"

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        self.captured = request
        return request


class GavioTestKit:
    def __init__(
        self,
        interceptors: list[Interceptor] | None = None,
        provider: ProviderAdapter | None = None,
        model: str = "mock",
    ) -> None:
        self._interceptors = list(interceptors or [])
        self._provider = provider or MockProvider()
        self._model = model
        self._capture = _CaptureInterceptor()
        self.ctx: InterceptorContext | None = None
        self.response: GavioResponse | None = None

    async def run(self, messages: list[Message], **options: object) -> GavioResponse:
        request = GavioRequest(
            messages=messages,
            model=self._model,
            provider=Provider.coerce(self._provider.provider_name),
            options=dict(options),
        )
        ctx = InterceptorContext(trace_id=request.trace_id)

        all_interceptors = [*self._interceptors, self._capture]
        policies = [i for i in all_interceptors if isinstance(i, ExecutorPolicy)]
        regular = [i for i in all_interceptors if not isinstance(i, ExecutorPolicy)]
        chain = InterceptorChain(regular)

        async def base(req: GavioRequest) -> GavioResponse:
            return await self._provider.complete(req)

        executor: Executor = base
        for policy in reversed(policies):
            executor = self._wrap(policy, executor, ctx)

        self.response = await chain.execute(request, ctx, executor)
        self.ctx = ctx
        return self.response

    @staticmethod
    def _wrap(policy: ExecutorPolicy, inner: Executor, ctx: InterceptorContext):
        async def wrapped(req: GavioRequest) -> GavioResponse:
            return await policy.around(req, ctx, inner)

        return wrapped

    # ---- assertions / inspection -------------------------------------------

    @property
    def redacted_request(self) -> GavioRequest | None:
        return self._capture.captured

    @property
    def audit_record(self):
        return self.response.audit if self.response else None

    def pii_detected(self, entity_type: str | None = None) -> bool:
        if self.ctx is None:
            return False
        if entity_type is None:
            return bool(self.ctx.pii_entity_types)
        return entity_type in self.ctx.pii_entity_types
