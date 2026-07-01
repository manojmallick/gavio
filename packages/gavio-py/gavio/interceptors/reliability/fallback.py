"""FallbackChain (F-REL-02) — route to a secondary provider on failure."""

from __future__ import annotations

import logging

from ...context import InterceptorContext
from ...exceptions import ProviderError
from ...interceptors.chain import Executor
from ...request import GavioRequest
from ...response import GavioResponse
from ...types import Provider
from .policy import ExecutorPolicy

logger = logging.getLogger("gavio.fallback")


class FallbackChain(ExecutorPolicy):
    """Try the primary executor; on a provider error, try fallback adapters.

    Each fallback is a provider adapter (anything with an async ``complete``).
    The request's ``provider``/``model`` are rewritten per fallback so the
    audit record reflects which provider actually answered.
    """

    def __init__(self, fallbacks: list) -> None:
        if not fallbacks:
            raise ValueError("FallbackChain requires at least one fallback adapter")
        self.fallbacks = fallbacks

    @property
    def name(self) -> str:
        return "fallback"

    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        ctx.mark_fired(self.name)
        try:
            return await call_next(request)
        except ProviderError as primary_error:
            logger.warning(
                "fallback: primary failed (%s); trying %d fallback(s)",
                type(primary_error).__name__,
                len(self.fallbacks),
            )
            last_error: Exception = primary_error
            for adapter in self.fallbacks:
                try:
                    rerouted = request.copy_with_messages(request.messages)
                    rerouted.provider = Provider.coerce(adapter.provider_name)
                    return await adapter.complete(rerouted)
                except ProviderError as error:
                    last_error = error
                    logger.warning(
                        "fallback: %s also failed (%s)",
                        adapter.provider_name,
                        type(error).__name__,
                    )
            raise last_error from primary_error
