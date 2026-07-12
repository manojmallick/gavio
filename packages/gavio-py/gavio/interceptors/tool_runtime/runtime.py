"""Tool Runtime interceptor for validating tool calls/results.

The runtime consumes ``request.metadata["tools"]`` (or ``toolContext`` /
``tool_context`` via ``InterceptorContext.tools``) and records a compact
decision object under ``ctx.tools["runtime"]`` plus Inspector decision state.
"""

from __future__ import annotations

import json
from collections import Counter, defaultdict
from collections.abc import Iterable
from datetime import UTC, datetime
from typing import Any

from ...context import InterceptorContext
from ...exceptions import ToolRuntimeError
from ...request import GavioRequest
from ..base import Interceptor


class ToolRuntimeInterceptor(Interceptor):
    """Validate tool metadata before the provider call.

    Parameters:
        on_failure: ``"warn"`` records violations, ``"error"`` blocks unless
            the gateway is in dry-run mode.
        max_age_seconds: default freshness budget when calls omit
            ``ttl_seconds`` / ``max_age_seconds``.
        conflict_keys: result keys to compare across calls for conflicts.
    """

    def __init__(
        self,
        *,
        on_failure: str = "warn",
        max_age_seconds: float | None = None,
        conflict_keys: Iterable[str] | None = None,
    ) -> None:
        if on_failure not in {"warn", "error"}:
            raise ValueError("on_failure must be 'warn' or 'error'")
        self.on_failure = on_failure
        self.max_age_seconds = max_age_seconds
        self.conflict_keys = list(conflict_keys or [])

    @property
    def name(self) -> str:
        return "tool_runtime"

    async def before(self, request: GavioRequest, ctx: InterceptorContext) -> GavioRequest:
        ctx.mark_fired(self.name)
        decision = analyze_tool_runtime(
            ctx.tools,
            max_age_seconds=self.max_age_seconds,
            conflict_keys=self.conflict_keys,
        )
        ctx.tools["runtime"] = decision
        ctx.inspect("tool_runtime", decision)
        for conflict in decision["conflicts"]:
            ctx.record_governance_event({"kind": "tool_conflict", **conflict})
        if decision["violations"] and self.on_failure == "error" and not ctx.dry_run:
            messages = [v["message"] for v in decision["violations"]]
            raise ToolRuntimeError("; ".join(messages))
        return request


def analyze_tool_runtime(
    tools: dict[str, Any] | None,
    *,
    max_age_seconds: float | None = None,
    conflict_keys: Iterable[str] | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Return schema/freshness/conflict/provenance decisions for tool context."""

    tools = dict(tools or {})
    calls = _calls(tools)
    reference_time = now or _parse_time(_first(tools, "now", "evaluated_at")) or datetime.now(UTC)
    default_max_age = _number(_first(tools, "max_age_seconds", "maxAgeSeconds"))
    if max_age_seconds is not None:
        default_max_age = float(max_age_seconds)

    violations: list[dict[str, Any]] = []
    provenance: list[dict[str, Any]] = []
    confidence_values: list[float] = []

    for call in calls:
        tool_id = _tool_id(call)
        tool_name = _tool_name(call)
        result = _record(_first(call, "result", "output"))
        input_value = _record(_first(call, "input", "arguments", "args"))

        for label, value, schema in (
            ("input", input_value, _record(_first(call, "input_schema", "inputSchema"))),
            ("output", result, _record(_first(call, "output_schema", "outputSchema", "schema"))),
        ):
            if schema:
                violations.extend(_validate_schema(value, schema, label, tool_id, tool_name))

        created_at = _parse_time(
            _first(call, "created_at", "createdAt", "timestamp", "observed_at")
        )
        ttl = _number(_first(call, "ttl_seconds", "ttlSeconds", "max_age_seconds", "maxAgeSeconds"))
        if ttl is None:
            ttl = default_max_age
        if created_at is not None and ttl is not None:
            age = max(0.0, (reference_time - created_at).total_seconds())
            if age > ttl:
                violations.append(
                    _violation(
                        "freshness",
                        tool_id,
                        tool_name,
                        f"tool result is stale: age {age:.1f}s exceeds {ttl:.1f}s",
                        age_seconds=round(age, 3),
                        max_age_seconds=ttl,
                    )
                )

        confidence = _number(call.get("confidence"))
        if confidence is not None:
            confidence_values.append(confidence)
        provenance.append(
            {
                "tool_id": tool_id,
                "tool_name": tool_name,
                "source": str(_first(call, "source", "provider", "provenance") or "unknown"),
                "created_at": created_at.isoformat().replace("+00:00", "Z")
                if created_at is not None
                else None,
                "cache_hit": bool(_first(call, "cache_hit", "cacheHit") or False),
                "confidence": confidence,
                "result_keys": sorted(result.keys()),
            }
        )

    conflicts = _conflicts(calls, tools, conflict_keys)
    confidence = _overall_confidence(conflicts, confidence_values)
    return {
        "call_count": len(calls),
        "violations": violations,
        "conflicts": conflicts,
        "confidence": confidence,
        "provenance": provenance,
    }


def _calls(tools: dict[str, Any]) -> list[dict[str, Any]]:
    raw = _first(tools, "calls", "tool_calls", "toolCalls", "results")
    if not isinstance(raw, list):
        return []
    return [dict(item) for item in raw if isinstance(item, dict)]


def _conflicts(
    calls: list[dict[str, Any]],
    tools: dict[str, Any],
    conflict_keys: Iterable[str] | None,
) -> list[dict[str, Any]]:
    keys = list(conflict_keys or [])
    configured = _first(tools, "conflict_keys", "conflictKeys")
    if isinstance(configured, list):
        keys.extend(str(key) for key in configured)
    keys = sorted(set(keys))
    conflicts: list[dict[str, Any]] = []
    for key in keys:
        buckets: dict[str, list[str]] = defaultdict(list)
        for call in calls:
            result = _record(_first(call, "result", "output"))
            if key in result:
                buckets[_stable_value(result[key])].append(_tool_id(call))
        if len(buckets) > 1:
            counts = Counter({value: len(ids) for value, ids in buckets.items()})
            total = sum(counts.values())
            confidence = round(max(counts.values()) / total, 4) if total else 1.0
            conflicts.append(
                {
                    "key": key,
                    "values": sorted(buckets.keys()),
                    "tool_ids": sorted(tool_id for ids in buckets.values() for tool_id in ids),
                    "confidence": confidence,
                }
            )
    return conflicts


def _validate_schema(
    value: dict[str, Any],
    schema: dict[str, Any],
    label: str,
    tool_id: str,
    tool_name: str,
) -> list[dict[str, Any]]:
    violations: list[dict[str, Any]] = []
    required = schema.get("required")
    if isinstance(required, list):
        for field in required:
            key = str(field)
            if key not in value:
                violations.append(_violation(
                    "schema",
                    tool_id,
                    tool_name,
                    f"{label} missing required field {key}",
                ))
    properties = schema.get("properties")
    if isinstance(properties, dict):
        for field, spec in properties.items():
            key = str(field)
            if key in value and not _matches_type(value[key], spec):
                violations.append(
                    _violation("schema", tool_id, tool_name, f"{label}.{key} has invalid type")
                )
    return violations


def _matches_type(value: Any, spec: Any) -> bool:
    expected = spec.get("type") if isinstance(spec, dict) else spec
    if isinstance(expected, list):
        return any(_matches_type(value, item) for item in expected)
    expected = str(expected)
    if expected == "string":
        return isinstance(value, str)
    if expected == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "object":
        return isinstance(value, dict)
    if expected == "array":
        return isinstance(value, list)
    if expected == "null":
        return value is None
    return True


def _overall_confidence(conflicts: list[dict[str, Any]], values: list[float]) -> float:
    if conflicts:
        return min(float(c["confidence"]) for c in conflicts)
    if values:
        return round(sum(values) / len(values), 4)
    return 1.0


def _violation(
    kind: str, tool_id: str, tool_name: str, message: str, **extra: Any
) -> dict[str, Any]:
    return {
        "kind": kind,
        "tool_id": tool_id,
        "tool_name": tool_name,
        "message": message,
        **extra,
    }


def _tool_id(call: dict[str, Any]) -> str:
    return str(_first(call, "id", "tool_call_id", "toolCallId") or _tool_name(call))


def _tool_name(call: dict[str, Any]) -> str:
    return str(_first(call, "name", "tool", "tool_name", "toolName") or "tool")


def _first(source: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in source:
            return source[key]
    return None


def _record(value: Any) -> dict[str, Any]:
    return dict(value) if isinstance(value, dict) else {}


def _number(value: Any) -> float | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return float(value)
    return None


def _parse_time(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


def _stable_value(value: Any) -> str:
    if isinstance(value, (str, int, float, bool)) or value is None:
        return str(value)
    return json.dumps(_stable_normalize(value), sort_keys=True, separators=(",", ":"), default=str)


def _stable_normalize(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): _stable_normalize(value[key])
            for key in sorted(value.keys(), key=lambda item: str(item))
        }
    if isinstance(value, list):
        return [_stable_normalize(item) for item in value]
    return value
