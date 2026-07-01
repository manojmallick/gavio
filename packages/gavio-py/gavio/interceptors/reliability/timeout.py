"""TimeoutPolicy (F-REL-07) — per-request timeout enforcement."""

from __future__ import annotations

import asyncio

from ...context import InterceptorContext
from ...exceptions import TimeoutError as GavioTimeoutError
from ...interceptors.chain import Executor
from ...request import GavioRequest
from ...response import GavioResponse
from .policy import ExecutorPolicy


class TimeoutPolicy(ExecutorPolicy):
    """Abort the provider call if it exceeds ``timeout_seconds``."""

    def __init__(self, timeout_seconds: float = 30.0) -> None:
        if timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be > 0")
        self.timeout_seconds = timeout_seconds

    @property
    def name(self) -> str:
        return "timeout"

    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        ctx.mark_fired(self.name)
        try:
            return await asyncio.wait_for(
                call_next(request), timeout=self.timeout_seconds
            )
        except asyncio.TimeoutError as error:
            raise GavioTimeoutError(
                f"Request exceeded {self.timeout_seconds}s timeout"
            ) from error
