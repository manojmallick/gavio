"""Cost Governance v2 budget policy evaluation.

This module keeps the public budget decision contract independent from any
particular storage backend. The default store is in-memory so core remains
dependency-free; Redis/Postgres stores can implement the same tiny protocol.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from typing import Any, Protocol

from ...context import InterceptorContext
from ...exceptions import BudgetExceededError
from ...request import GavioRequest
from ...response import GavioResponse
from ..base import Interceptor

_SCOPE_STATE = "budget_v2:scope"
_DECISION_STATE = "budget_v2:decision"


@dataclass(frozen=True)
class BudgetPolicy:
    """Structured Cost Governance v2 policy.

    The wire shape is camelCase to match ``spec/BudgetPolicy.schema.json``;
    Python attributes are snake_case.
    """

    id: str
    scope_type: str
    window: str
    limit_usd: float
    hard_limit_action: str
    scope_value: str | None = None
    soft_limit_ratio: float = 0.8
    alert_thresholds: tuple[float, ...] = ()
    fallback_model: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> BudgetPolicy:
        return cls(
            id=str(data["id"]),
            scope_type=str(data.get("scopeType", data.get("scope_type", "global"))),
            scope_value=_optional_str(data.get("scopeValue", data.get("scope_value"))),
            window=str(data.get("window", "daily")),
            limit_usd=float(data.get("limitUsd", data.get("limit_usd", 0.0))),
            soft_limit_ratio=float(
                data.get("softLimitRatio", data.get("soft_limit_ratio", 0.8))
            ),
            hard_limit_action=str(
                data.get("hardLimitAction", data.get("hard_limit_action", "block"))
            ),
            alert_thresholds=tuple(
                float(v)
                for v in data.get("alertThresholds", data.get("alert_thresholds", ()))
            ),
            fallback_model=_optional_str(
                data.get("fallbackModel", data.get("fallback_model"))
            ),
            metadata=dict(data.get("metadata") or {}),
        )

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": self.id,
            "scopeType": self.scope_type,
            "window": self.window,
            "limitUsd": self.limit_usd,
            "softLimitRatio": self.soft_limit_ratio,
            "hardLimitAction": self.hard_limit_action,
            "alertThresholds": list(self.alert_thresholds),
            "metadata": dict(self.metadata),
        }
        if self.scope_value is not None:
            out["scopeValue"] = self.scope_value
        if self.fallback_model is not None:
            out["fallbackModel"] = self.fallback_model
        return out


@dataclass(frozen=True)
class BudgetDecision:
    """Auditable Cost Governance v2 decision."""

    policy_id: str
    scope: str
    window: str
    allowed: bool
    action: str
    current_spend_usd: float
    projected_spend_usd: float
    remaining_usd: float
    threshold_status: str
    reason: str
    target_model: str | None = None
    alert_thresholds_crossed: tuple[float, ...] = ()
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "policyId": self.policy_id,
            "scope": self.scope,
            "window": self.window,
            "allowed": self.allowed,
            "action": self.action,
            "currentSpendUsd": self.current_spend_usd,
            "projectedSpendUsd": self.projected_spend_usd,
            "remainingUsd": self.remaining_usd,
            "thresholdStatus": self.threshold_status,
            "reason": self.reason,
            "alertThresholdsCrossed": list(self.alert_thresholds_crossed),
            "metadata": dict(self.metadata),
        }
        if self.target_model is not None:
            out["targetModel"] = self.target_model
        return out


class BudgetStore(Protocol):
    """Minimal spend store protocol for Cost Governance v2."""

    def get(self, scope: str) -> float:
        """Return current spend for a resolved budget scope."""

    def add(self, scope: str, cost_usd: float) -> float:
        """Add spend for a resolved budget scope and return the new total."""


class InMemoryBudgetStore:
    """Dependency-free budget store for tests, local apps, and examples."""

    def __init__(self, initial: dict[str, float] | None = None) -> None:
        self._spend = dict(initial or {})

    def get(self, scope: str) -> float:
        return self._spend.get(scope, 0.0)

    def add(self, scope: str, cost_usd: float) -> float:
        total = self.get(scope) + cost_usd
        self._spend[scope] = total
        return total


def evaluate_budget(
    policy: BudgetPolicy | dict[str, Any],
    *,
    scope: str,
    current_spend_usd: float,
    request_cost_usd: float,
) -> BudgetDecision:
    """Evaluate a policy against current spend and projected request cost."""

    policy = _coerce_policy(policy)
    current = round8(max(current_spend_usd, 0.0))
    projected = round8(max(current_spend_usd + request_cost_usd, 0.0))
    remaining = round8(max(policy.limit_usd - projected, 0.0))
    ratio_before = _ratio(current, policy.limit_usd)
    ratio_after = _ratio(projected, policy.limit_usd)
    crossed = tuple(
        threshold
        for threshold in sorted(set(policy.alert_thresholds))
        if ratio_before < threshold <= ratio_after
    )

    if projected > policy.limit_usd:
        action = policy.hard_limit_action
        if action == "fallback":
            return _decision(
                policy,
                scope,
                allowed=True,
                action="fallback",
                current=current,
                projected=projected,
                remaining=remaining,
                threshold_status="hard_limit",
                reason="fallback_after_hard_limit",
                target_model=policy.fallback_model,
                crossed=crossed,
            )
        if action == "downgrade_model":
            return _decision(
                policy,
                scope,
                allowed=True,
                action="downgrade_model",
                current=current,
                projected=projected,
                remaining=remaining,
                threshold_status="hard_limit",
                reason="downgrade_after_hard_limit",
                target_model=policy.fallback_model,
                crossed=crossed,
            )
        if action == "dry_run":
            return _decision(
                policy,
                scope,
                allowed=True,
                action="dry_run",
                current=current,
                projected=projected,
                remaining=remaining,
                threshold_status="hard_limit",
                reason="hard_limit_dry_run",
                crossed=crossed,
            )
        return _decision(
            policy,
            scope,
            allowed=False,
            action="block",
            current=current,
            projected=projected,
            remaining=remaining,
            threshold_status="hard_limit",
            reason="hard_limit_exceeded",
            crossed=crossed,
        )

    if ratio_after >= policy.soft_limit_ratio:
        return _decision(
            policy,
            scope,
            allowed=True,
            action="warn",
            current=current,
            projected=projected,
            remaining=remaining,
            threshold_status="soft_limit",
            reason="soft_limit_exceeded",
            crossed=crossed,
        )

    return _decision(
        policy,
        scope,
        allowed=True,
        action="allow",
        current=current,
        projected=projected,
        remaining=remaining,
        threshold_status="ok",
        reason="under_budget",
        crossed=crossed,
    )


class BudgetPolicyControl(Interceptor):
    """Apply a Cost Governance v2 policy before each provider call.

    ``estimated_request_cost_usd`` is intentionally explicit: request cost is
    only known after provider execution, so production callers can provide a
    conservative estimate while the after-hook records actual spend.
    """

    def __init__(
        self,
        policy: BudgetPolicy | dict[str, Any],
        *,
        store: BudgetStore | None = None,
        estimated_request_cost_usd: float = 0.0,
    ) -> None:
        self.policy = _coerce_policy(policy)
        self.store = store or InMemoryBudgetStore()
        self.estimated_request_cost_usd = estimated_request_cost_usd

    @property
    def name(self) -> str:
        return "budget_policy"

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        scope = resolve_policy_scope(self.policy, request, ctx)
        current = self.store.get(scope)
        decision = evaluate_budget(
            self.policy,
            scope=scope,
            current_spend_usd=current,
            request_cost_usd=self.estimated_request_cost_usd,
        )
        decision_dict = decision.to_dict()
        ctx.state[_SCOPE_STATE] = scope
        ctx.state[_DECISION_STATE] = decision_dict
        ctx.inspect("budget_decision", decision_dict)
        if decision.threshold_status != "ok":
            ctx.record_governance_event(
                {"kind": "budget", "decision": decision_dict, "policyId": self.policy.id}
            )
        if decision.action in ("fallback", "downgrade_model") and decision.target_model:
            if request.model != decision.target_model:
                return replace(request, model=decision.target_model)
        if not decision.allowed:
            raise BudgetExceededError(
                f"budget policy {self.policy.id} exceeded for {scope}: "
                f"projected ${decision.projected_spend_usd:.4f} > "
                f"${self.policy.limit_usd:.4f}"
            )
        return request

    async def after(
        self, response: GavioResponse, ctx: InterceptorContext
    ) -> GavioResponse:
        scope = ctx.state.get(_SCOPE_STATE)
        if isinstance(scope, str):
            self.store.add(scope, response.cost_usd)
        return response


def resolve_policy_scope(
    policy: BudgetPolicy,
    request: GavioRequest,
    ctx: InterceptorContext | None = None,
    *,
    now: datetime | None = None,
) -> str:
    """Resolve policy scope and window into a stable spend key."""

    value = policy.scope_value or _request_scope_value(policy.scope_type, request, ctx)
    prefix = "global" if policy.scope_type == "global" else f"{policy.scope_type}:{value}"
    return f"{prefix}|{window_bucket(policy.window, now=now)}"


def window_bucket(window: str, *, now: datetime | None = None) -> str:
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None:
        now = now.replace(tzinfo=timezone.utc)
    if window in ("daily", "day"):
        return now.strftime("%Y-%m-%d")
    if window in ("weekly", "week"):
        year, week, _ = now.isocalendar()
        return f"{year}-W{week:02d}"
    if window in ("monthly", "month"):
        return now.strftime("%Y-%m")
    if window in ("rolling", "total"):
        return window
    return "total"


def round8(value: float) -> float:
    return round(value, 8)


def _decision(
    policy: BudgetPolicy,
    scope: str,
    *,
    allowed: bool,
    action: str,
    current: float,
    projected: float,
    remaining: float,
    threshold_status: str,
    reason: str,
    target_model: str | None = None,
    crossed: tuple[float, ...] = (),
) -> BudgetDecision:
    return BudgetDecision(
        policy_id=policy.id,
        scope=scope,
        window=policy.window,
        allowed=allowed,
        action=action,
        current_spend_usd=current,
        projected_spend_usd=projected,
        remaining_usd=remaining,
        threshold_status=threshold_status,
        reason=reason,
        target_model=target_model,
        alert_thresholds_crossed=crossed,
    )


def _request_scope_value(
    scope_type: str, request: GavioRequest, ctx: InterceptorContext | None
) -> str:
    if scope_type == "global":
        return "global"
    if scope_type == "agent":
        return (ctx.agent_id if ctx else request.agent_id) or "unknown"
    if scope_type == "session":
        return (ctx.session_id if ctx else request.session_id) or "unknown"
    if scope_type == "model":
        return request.model
    if scope_type == "request":
        return request.trace_id
    return _dimension(request.metadata or {}, scope_type) or "unknown"


def _dimension(metadata: dict[str, Any], key: str) -> str | None:
    nested = metadata.get("costDimensions")
    nested_snake = metadata.get("cost_dimensions")
    return _read_dimension(nested, key) or _read_dimension(nested_snake, key) or _read_dimension(
        metadata, key
    )


def _read_dimension(source: object, key: str) -> str | None:
    if not isinstance(source, dict):
        return None
    aliases = {
        "tenant": ("tenant", "tenantId", "tenant_id"),
        "team": ("team", "teamId", "team_id"),
        "feature": ("feature", "featureId", "feature_id"),
        "user": ("user", "userId", "user_id"),
        "endpoint": ("endpoint",),
        "environment": ("environment",),
        "workflow": ("workflow",),
        "tool": ("tool",),
    }.get(key, (key,))
    for alias in aliases:
        value = source.get(alias)
        if isinstance(value, str) and value.strip():
            return value.strip()
        if isinstance(value, (int, float, bool)):
            return str(value)
    return None


def _coerce_policy(policy: BudgetPolicy | dict[str, Any]) -> BudgetPolicy:
    return policy if isinstance(policy, BudgetPolicy) else BudgetPolicy.from_dict(policy)


def _ratio(spend: float, limit: float) -> float:
    if limit <= 0:
        return float("inf") if spend > 0 else 0.0
    return spend / limit


def _optional_str(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
