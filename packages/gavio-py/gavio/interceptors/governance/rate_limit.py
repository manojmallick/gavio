"""RateLimiter (F-GOV-03) — fixed-window requests/tokens per minute per scope."""

from __future__ import annotations

import logging
import time

from ...context import InterceptorContext
from ...exceptions import RateLimitExceededError
from ...request import GavioRequest
from ...response import GavioResponse
from ..base import Interceptor

logger = logging.getLogger("gavio.ratelimit")


def _scope_key(scope: str, ctx: InterceptorContext) -> str:
    if scope == "agent":
        return f"agent:{ctx.agent_id or 'unknown'}"
    if scope == "session":
        return f"session:{ctx.session_id or 'unknown'}"
    return "global"


class RateLimiter(Interceptor):
    """Enforce per-minute request and token ceilings using fixed windows.

    Requests are counted before the call; tokens are added after (from the
    response usage) and block the *next* request in the same minute once the
    ceiling is reached.
    """

    def __init__(
        self,
        max_requests_per_minute: int | None = None,
        max_tokens_per_minute: int | None = None,
        scope: str = "global",
    ) -> None:
        self.max_requests_per_minute = max_requests_per_minute
        self.max_tokens_per_minute = max_tokens_per_minute
        self.scope = scope
        # key -> {"minute": int, "requests": int, "tokens": int}
        self._windows: dict[str, dict[str, int]] = {}

    @property
    def name(self) -> str:
        return "rate_limiter"

    def _window(self, ctx: InterceptorContext) -> dict[str, int]:
        minute = int(time.time() // 60)
        key = _scope_key(self.scope, ctx)
        w = self._windows.get(key)
        if w is None or w["minute"] != minute:
            w = {"minute": minute, "requests": 0, "tokens": 0}
            self._windows[key] = w
        return w

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        w = self._window(ctx)
        if (
            self.max_requests_per_minute is not None
            and w["requests"] >= self.max_requests_per_minute
        ):
            raise RateLimitExceededError(
                f"rate limit: {self.max_requests_per_minute} requests/min exceeded"
            )
        if (
            self.max_tokens_per_minute is not None
            and w["tokens"] >= self.max_tokens_per_minute
        ):
            raise RateLimitExceededError(
                f"rate limit: {self.max_tokens_per_minute} tokens/min exceeded"
            )
        w["requests"] += 1
        return request

    async def after(
        self, response: GavioResponse, ctx: InterceptorContext
    ) -> GavioResponse:
        if self.max_tokens_per_minute is not None:
            self._window(ctx)["tokens"] += response.usage.total_tokens
        return response
