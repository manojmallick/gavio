"""RetryInterceptor (F-REL-01) — exponential backoff with jitter."""

from __future__ import annotations

import asyncio
import logging
import os

from ...context import InterceptorContext
from ...exceptions import (
    ProviderUnavailableError,
    RateLimitError,
    ServerError,
)
from ...exceptions import (
    TimeoutError as GavioTimeoutError,
)
from ...interceptors.chain import Executor
from ...request import GavioRequest
from ...response import GavioResponse
from .policy import ExecutorPolicy

logger = logging.getLogger("gavio.retry")

_DEFAULT_RETRY_ON: tuple[type[Exception], ...] = (
    RateLimitError,
    GavioTimeoutError,
    ServerError,
    ProviderUnavailableError,
)


class RetryInterceptor(ExecutorPolicy):
    """Retry the provider call on transient errors with capped exponential backoff."""

    def __init__(
        self,
        max_attempts: int = 3,
        base_delay_ms: int = 500,
        max_delay_ms: int = 10_000,
        jitter: bool = True,
        retry_on: tuple[type[Exception], ...] | None = None,
    ) -> None:
        if max_attempts < 1:
            raise ValueError("max_attempts must be >= 1")
        self.max_attempts = max_attempts
        self.base_delay_ms = base_delay_ms
        self.max_delay_ms = max_delay_ms
        self.jitter = jitter
        self.retry_on = retry_on or _DEFAULT_RETRY_ON

    @property
    def name(self) -> str:
        return "retry"

    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        ctx.mark_fired(self.name)
        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                return await call_next(request)
            except self.retry_on as error:
                last_error = error
                if attempt >= self.max_attempts:
                    break
                delay = self._delay_seconds(attempt)
                logger.warning(
                    "retry: attempt %d/%d failed (%s); backing off %.0fms",
                    attempt,
                    self.max_attempts,
                    type(error).__name__,
                    delay * 1000,
                )
                await asyncio.sleep(delay)
        assert last_error is not None
        raise last_error

    def _delay_seconds(self, attempt: int) -> float:
        # Exponential: base * 2^(attempt-1), capped, with optional full jitter.
        raw_ms = self.base_delay_ms * (2 ** (attempt - 1))
        capped_ms = min(raw_ms, self.max_delay_ms)
        if self.jitter:
            # full jitter in [0, capped] using os.urandom (no global RNG state)
            frac = int.from_bytes(os.urandom(2), "big") / 0xFFFF
            capped_ms *= frac
        return capped_ms / 1000.0
