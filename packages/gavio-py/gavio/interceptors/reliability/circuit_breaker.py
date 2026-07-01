"""CircuitBreaker (F-REL-03) — open/half-open/closed state machine."""

from __future__ import annotations

import asyncio
import logging
import time
from enum import Enum

from ...context import InterceptorContext
from ...exceptions import CircuitOpenError, ProviderError
from ...interceptors.chain import Executor
from ...request import GavioRequest
from ...response import GavioResponse
from ..executor import ExecutorPolicy

logger = logging.getLogger("gavio.circuit")


class CircuitState(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


class CircuitBreaker(ExecutorPolicy):
    """Stop hammering a failing provider.

    Counts consecutive provider failures; after ``failure_threshold`` the
    circuit **opens** and calls fast-fail with :class:`CircuitOpenError` for
    ``recovery_timeout_seconds``. It then goes **half-open**, allowing up to
    ``half_open_max_calls`` probe calls: a success **closes** it, a failure
    **re-opens** it.
    """

    def __init__(
        self,
        failure_threshold: int = 5,
        recovery_timeout_seconds: float = 30.0,
        half_open_max_calls: int = 2,
    ) -> None:
        if failure_threshold < 1:
            raise ValueError("failure_threshold must be >= 1")
        self.failure_threshold = failure_threshold
        self.recovery_timeout_seconds = recovery_timeout_seconds
        self.half_open_max_calls = half_open_max_calls

        self._state = CircuitState.CLOSED
        self._failures = 0
        self._opened_at = 0.0
        self._half_open_calls = 0
        self._lock = asyncio.Lock()

    @property
    def name(self) -> str:
        return "circuit_breaker"

    @property
    def state(self) -> CircuitState:
        return self._state

    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        ctx.mark_fired(self.name)
        await self._admit()  # raises CircuitOpenError if not allowed through
        try:
            response = await call_next(request)
        except ProviderError:
            await self._on_failure()
            raise
        else:
            await self._on_success()
            return response

    async def _admit(self) -> None:
        async with self._lock:
            if self._state == CircuitState.OPEN:
                if time.monotonic() - self._opened_at >= self.recovery_timeout_seconds:
                    self._state = CircuitState.HALF_OPEN
                    self._half_open_calls = 0
                    logger.info("circuit half-open: probing")
                else:
                    raise CircuitOpenError("circuit is open")
            if self._state == CircuitState.HALF_OPEN:
                if self._half_open_calls >= self.half_open_max_calls:
                    raise CircuitOpenError("circuit half-open probe limit reached")
                self._half_open_calls += 1

    async def _on_success(self) -> None:
        async with self._lock:
            if self._state == CircuitState.HALF_OPEN:
                logger.info("circuit closed: probe succeeded")
            self._state = CircuitState.CLOSED
            self._failures = 0

    async def _on_failure(self) -> None:
        async with self._lock:
            if self._state == CircuitState.HALF_OPEN:
                self._trip()
                return
            self._failures += 1
            if self._failures >= self.failure_threshold:
                self._trip()

    def _trip(self) -> None:
        self._state = CircuitState.OPEN
        self._opened_at = time.monotonic()
        logger.warning("circuit opened after %d failure(s)", self._failures)
