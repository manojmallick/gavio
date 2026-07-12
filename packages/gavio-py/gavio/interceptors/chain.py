"""InterceptorChain — runs the pre/post pipeline around the provider call."""

from __future__ import annotations

import logging
import time
from collections.abc import Awaitable, Callable
from typing import TYPE_CHECKING

from ..context import InterceptorContext
from ..request import GavioRequest
from ..response import GavioResponse
from .base import Interceptor

if TYPE_CHECKING:
    from ..inspector.emitter import TraceEmitter

logger = logging.getLogger("gavio.chain")

# A function that takes the final request and returns a response (the provider call).
Executor = Callable[[GavioRequest], Awaitable[GavioResponse]]


class InterceptorChain:
    """Ordered list of interceptors wrapping an executor.

    ``before`` hooks fire in order; the executor runs; ``after`` hooks fire in
    reverse order (onion model). If any stage raises, every interceptor's
    ``on_error`` is invoked before the error propagates.
    """

    def __init__(self, interceptors: list[Interceptor]) -> None:
        self._interceptors = list(interceptors)

    @property
    def interceptors(self) -> list[Interceptor]:
        return list(self._interceptors)

    async def execute(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        executor: Executor,
        emitter: TraceEmitter | None = None,
    ) -> GavioResponse:
        try:
            for interceptor in self._interceptors:
                if ctx.dry_run and not interceptor.dry_run_safe:
                    logger.debug("dry-run: skipping %s.before", interceptor.name)
                    continue
                if emitter is None:
                    request = await interceptor.before(request, ctx)
                else:
                    request = await self._observed_before(interceptor, request, ctx, emitter)
                ctx.mark_fired(interceptor.name)

            response = await executor(request)

            for interceptor in reversed(self._interceptors):
                if ctx.dry_run and not interceptor.dry_run_safe:
                    continue
                if emitter is None:
                    response = await interceptor.after(response, ctx)
                else:
                    response = await self._observed_after(interceptor, response, ctx, emitter)

            response.interceptors_fired = list(ctx.interceptors_fired)
            return response
        except Exception as error:  # noqa: BLE001 - re-raised after notifying
            if emitter is not None:
                emitter.emit_pending_governance(ctx)
            for interceptor in self._interceptors:
                try:
                    await interceptor.on_error(error, ctx)
                except Exception:  # noqa: BLE001
                    logger.exception("on_error failed in %s", interceptor.name)
            raise

    @staticmethod
    async def _observed_before(
        interceptor: Interceptor,
        request: GavioRequest,
        ctx: InterceptorContext,
        emitter: TraceEmitter,
    ) -> GavioRequest:
        """Run a before hook wrapped in interceptor.before.start/end events."""
        emitter.interceptor_start("before", interceptor.name)
        started = time.perf_counter_ns()
        try:
            result = await interceptor.before(request, ctx)
        except Exception:
            emitter.note_error("interceptor", interceptor.name)
            raise
        emitter.interceptor_before_end(interceptor.name, started, request, result, ctx)
        return result

    @staticmethod
    async def _observed_after(
        interceptor: Interceptor,
        response: GavioResponse,
        ctx: InterceptorContext,
        emitter: TraceEmitter,
    ) -> GavioResponse:
        """Run an after hook wrapped in interceptor.after.start/end events."""
        emitter.interceptor_start("after", interceptor.name)
        started = time.perf_counter_ns()
        try:
            result = await interceptor.after(response, ctx)
        except Exception:
            emitter.note_error("interceptor", interceptor.name)
            raise
        emitter.interceptor_after_end(interceptor.name, started, response, result, ctx)
        return result
