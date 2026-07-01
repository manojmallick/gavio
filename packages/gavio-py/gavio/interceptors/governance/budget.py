"""CostControl (F-GOV-02) — soft/hard budget caps per scope and window."""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from ...context import InterceptorContext
from ...exceptions import BudgetExceededError
from ...request import GavioRequest
from ...response import GavioResponse
from ..base import Interceptor

logger = logging.getLogger("gavio.budget")


def _scope_key(scope: str, ctx: InterceptorContext) -> str:
    if scope == "agent":
        return f"agent:{ctx.agent_id or 'unknown'}"
    if scope == "session":
        return f"session:{ctx.session_id or 'unknown'}"
    return "global"


def _window_bucket(window: str) -> str:
    now = datetime.now(timezone.utc)
    if window == "day":
        return now.strftime("%Y-%m-%d")
    if window == "month":
        return now.strftime("%Y-%m")
    return "total"


class CostControl(Interceptor):
    """Track spend per (scope, window) and block once the hard cap is hit.

    The check runs *before* the call using spend accrued so far; the request's
    own cost is added *after*. So the request that crosses the cap completes, and
    the next one in the window is blocked until the window resets.
    """

    def __init__(
        self,
        hard_cap_usd: float,
        soft_cap_usd: float | None = None,
        scope: str = "global",
        window: str = "day",
    ) -> None:
        self.hard_cap_usd = hard_cap_usd
        self.soft_cap_usd = soft_cap_usd
        self.scope = scope
        self.window = window
        self._spend: dict[str, float] = {}

    @property
    def name(self) -> str:
        return "cost_control"

    def spent(self, ctx: InterceptorContext) -> float:
        return self._spend.get(self._key(ctx), 0.0)

    def _key(self, ctx: InterceptorContext) -> str:
        return f"{_scope_key(self.scope, ctx)}|{_window_bucket(self.window)}"

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        spent = self.spent(ctx)
        if spent >= self.hard_cap_usd:
            raise BudgetExceededError(
                f"budget hard cap ${self.hard_cap_usd:.2f} reached for "
                f"{_scope_key(self.scope, ctx)} (spent ${spent:.4f})"
            )
        return request

    async def after(
        self, response: GavioResponse, ctx: InterceptorContext
    ) -> GavioResponse:
        key = self._key(ctx)
        total = self._spend.get(key, 0.0) + response.cost_usd
        self._spend[key] = total
        if self.soft_cap_usd is not None and total >= self.soft_cap_usd:
            logger.warning(
                "budget soft cap: $%.4f of $%.2f used for %s",
                total,
                self.soft_cap_usd,
                key,
            )
        return response
