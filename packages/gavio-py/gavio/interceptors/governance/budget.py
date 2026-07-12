"""CostControl (F-GOV-02) — soft/hard budget caps per scope and window."""

from __future__ import annotations

import logging
from dataclasses import replace
from datetime import datetime, timezone

from ...context import InterceptorContext
from ...exceptions import BudgetExceededError
from ...request import GavioRequest
from ...response import GavioResponse
from ..base import Interceptor

logger = logging.getLogger("gavio.budget")


_COST_CONTROL_KEY_STATE = "cost_control:budget_key"


def _scope_key(scope: str, request: GavioRequest, ctx: InterceptorContext) -> str:
    if scope == "agent":
        return f"agent:{ctx.agent_id or 'unknown'}"
    if scope == "session":
        return f"session:{ctx.session_id or 'unknown'}"
    if scope == "model":
        return f"model:{request.model}"
    if scope in ("tenant", "feature", "user"):
        return f"{scope}:{_dimension(request, scope)}"
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
        fallback_model: str | None = None,
    ) -> None:
        self.hard_cap_usd = hard_cap_usd
        self.soft_cap_usd = soft_cap_usd
        self.scope = scope
        self.window = window
        self.fallback_model = fallback_model
        self._spend: dict[str, float] = {}

    @property
    def name(self) -> str:
        return "cost_control"

    def spent(self, ctx: InterceptorContext) -> float:
        key = ctx.state.get(_COST_CONTROL_KEY_STATE)
        return self._spend.get(key, 0.0) if isinstance(key, str) else 0.0

    def _key(self, request: GavioRequest, ctx: InterceptorContext) -> str:
        return f"{_scope_key(self.scope, request, ctx)}|{_window_bucket(self.window)}"

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        key = self._key(request, ctx)
        ctx.state[_COST_CONTROL_KEY_STATE] = key
        spent = self._spend.get(key, 0.0)
        if spent >= self.hard_cap_usd:
            action = (
                "fallback"
                if self.fallback_model is not None and request.model != self.fallback_model
                else "block"
            )
            event = {
                "kind": "budget",
                "action": action,
                "scope": self.scope,
                "key": key,
                "spentUsd": round(spent, 4),
                "hardCapUsd": self.hard_cap_usd,
            }
            ctx.inspect("budget", event)
            ctx.record_governance_event(event)
            if action == "fallback":
                return replace(request, model=self.fallback_model)
            raise BudgetExceededError(
                f"budget hard cap ${self.hard_cap_usd:.2f} reached for "
                f"{key} (spent ${spent:.4f})"
            )
        return request

    async def after(
        self, response: GavioResponse, ctx: InterceptorContext
    ) -> GavioResponse:
        key = ctx.state.get(_COST_CONTROL_KEY_STATE)
        if not isinstance(key, str):
            key = f"global|{_window_bucket(self.window)}"
        total = self._spend.get(key, 0.0) + response.cost_usd
        self._spend[key] = total
        if self.soft_cap_usd is not None and total >= self.soft_cap_usd:
            event = {
                "kind": "budget",
                "action": "warn",
                "scope": self.scope,
                "key": key,
                "spentUsd": round(total, 4),
                "softCapUsd": self.soft_cap_usd,
            }
            ctx.inspect("budget", event)
            ctx.record_governance_event(event)
            logger.warning(
                "budget soft cap: $%.4f of $%.2f used for %s",
                total,
                self.soft_cap_usd,
                key,
            )
        return response


def _dimension(request: GavioRequest, key: str) -> str:
    metadata = request.metadata or {}
    nested = metadata.get("costDimensions")
    nested_snake = metadata.get("cost_dimensions")
    value = (
        _read_dimension(nested, key)
        or _read_dimension(nested_snake, key)
        or _read_dimension(metadata, key)
    )
    return value or "unknown"


def _read_dimension(source: object, key: str) -> str | None:
    if not isinstance(source, dict):
        return None
    aliases = {
        "tenant": ("tenant", "tenantId", "tenant_id"),
        "feature": ("feature", "featureId", "feature_id"),
        "user": ("user", "userId", "user_id"),
    }[key]
    for alias in aliases:
        value = source.get(alias)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, (int, float, bool)):
            return str(value)
    return None
