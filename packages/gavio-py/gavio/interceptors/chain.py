"""InterceptorChain — runs the pre/post pipeline around the provider call."""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable

from ..context import InterceptorContext
from ..request import GavioRequest
from ..response import GavioResponse
from .base import Interceptor

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
    ) -> GavioResponse:
        try:
            for interceptor in self._interceptors:
                if ctx.dry_run and not interceptor.dry_run_safe:
                    logger.debug("dry-run: skipping %s.before", interceptor.name)
                    continue
                request = await interceptor.before(request, ctx)
                ctx.mark_fired(interceptor.name)

            response = await executor(request)

            for interceptor in reversed(self._interceptors):
                if ctx.dry_run and not interceptor.dry_run_safe:
                    continue
                response = await interceptor.after(response, ctx)

            response.interceptors_fired = list(ctx.interceptors_fired)
            return response
        except Exception as error:  # noqa: BLE001 - re-raised after notifying
            for interceptor in self._interceptors:
                try:
                    await interceptor.on_error(error, ctx)
                except Exception:  # noqa: BLE001
                    logger.exception("on_error failed in %s", interceptor.name)
            raise
