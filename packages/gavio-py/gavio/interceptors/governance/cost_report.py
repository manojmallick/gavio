"""Cost Governance v2 reporting helpers."""

from __future__ import annotations

from typing import Any

from ...inspector.analytics import build_cost_report
from .budget_v2 import BudgetPolicy, round8


def build_cost_governance_report(
    summaries: list[dict[str, Any]],
    *,
    policies: list[BudgetPolicy | dict[str, Any]] | None = None,
    group_by: str | None = None,
    since: str | None = None,
    usage_elapsed_ratio: float = 1.0,
) -> dict[str, Any]:
    """Build a cost report and attach budget remaining/forecast rollups."""

    report = build_cost_report(summaries, group_by=group_by, since=since)
    coerced = [
        p if isinstance(p, BudgetPolicy) else BudgetPolicy.from_dict(p)
        for p in policies or []
    ]
    if not coerced:
        return report

    ratio = usage_elapsed_ratio if usage_elapsed_ratio > 0 else 1.0
    budgets: list[dict[str, Any]] = []
    for policy in coerced:
        current = _spend_for_policy(report, summaries, policy, group_by)
        forecast = round8(current / ratio)
        remaining = round8(max(policy.limit_usd - current, 0.0))
        status = _budget_status(policy, current, forecast)
        scope = _report_scope(policy)
        rollup = {
            "policyId": policy.id,
            "scope": scope,
            "window": policy.window,
            "limitUsd": policy.limit_usd,
            "currentSpendUsd": current,
            "remainingUsd": remaining,
            "forecastWindowSpendUsd": forecast,
            "status": status,
        }
        budgets.append(rollup)
        _attach_group_budget(report, policy, group_by, rollup)
    report["budgets"] = budgets
    return report


def _spend_for_policy(
    report: dict[str, Any],
    summaries: list[dict[str, Any]],
    policy: BudgetPolicy,
    group_by: str | None,
) -> float:
    if policy.scope_type == "global":
        return round8(float(report["total"].get("costUsd") or 0.0))
    if group_by == _group_by_name(policy.scope_type) and policy.scope_value:
        group = (report.get("groups") or {}).get(policy.scope_value)
        if isinstance(group, dict):
            return round8(float(group.get("costUsd") or 0.0))
    return round8(
        sum(
            float(summary.get("costUsd") or 0.0)
            for summary in summaries
            if _summary_matches_policy(summary, policy)
        )
    )


def _attach_group_budget(
    report: dict[str, Any],
    policy: BudgetPolicy,
    group_by: str | None,
    rollup: dict[str, Any],
) -> None:
    if not policy.scope_value or group_by != _group_by_name(policy.scope_type):
        return
    group = (report.get("groups") or {}).get(policy.scope_value)
    if not isinstance(group, dict):
        return
    group["budgetLimitUsd"] = rollup["limitUsd"]
    group["budgetRemainingUsd"] = rollup["remainingUsd"]
    group["forecastWindowSpendUsd"] = rollup["forecastWindowSpendUsd"]


def _summary_matches_policy(summary: dict[str, Any], policy: BudgetPolicy) -> bool:
    if policy.scope_type == "global":
        return True
    if policy.scope_value is None:
        return False
    value = summary.get(_summary_field(policy.scope_type))
    if value in (None, ""):
        value = (summary.get("costDimensions") or {}).get(_group_by_name(policy.scope_type))
    return str(value) == policy.scope_value


def _budget_status(policy: BudgetPolicy, current: float, forecast: float) -> str:
    if current >= policy.limit_usd:
        return "hard_limit"
    if current >= policy.limit_usd * policy.soft_limit_ratio:
        return "soft_limit"
    if forecast >= policy.limit_usd * policy.soft_limit_ratio:
        return "soft_limit"
    return "ok"


def _report_scope(policy: BudgetPolicy) -> str:
    if policy.scope_type == "global":
        return "global"
    return f"{policy.scope_type}:{policy.scope_value or 'unknown'}"


def _group_by_name(scope_type: str) -> str:
    if scope_type == "agent":
        return "agent_id"
    if scope_type == "session":
        return "session_id"
    return scope_type


def _summary_field(scope_type: str) -> str:
    if scope_type == "agent":
        return "agentId"
    if scope_type == "session":
        return "sessionId"
    if scope_type == "middleware_chain":
        return "middlewareChain"
    return scope_type
