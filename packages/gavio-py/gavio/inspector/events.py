"""Inspector event constructors — the InspectorEvent v1 wire format.

Every constructor returns a plain dict in the exact camelCase envelope from
``spec/InspectorEvent.schema.json``. Content-bearing fields exist only in the
``*_with_content`` / ``*_with_diff`` constructors, so the metadata-mode code
path has no content parameters at all — absence is structural, not a filter.

All content strings pass through :func:`mask_secrets` before they are stored,
so API keys, tokens, and connection strings never reach the inspector UI.
"""

from __future__ import annotations

from typing import Any

from .._ids import uuid7
from ..interceptors.pii.context import ScanContext
from ..interceptors.pii.scanners.secret import SecretScanner
from ..types import Message, TokenUsage

SCHEMA_VERSION = "1.0"
COST_DIMENSION_KEYS = ("feature", "tenant", "user", "endpoint", "environment", "workflow", "tool")
COST_DIMENSION_ALIASES = {
    "feature": ("feature", "featureId", "feature_id"),
    "tenant": ("tenant", "tenantId", "tenant_id"),
    "user": ("user", "userId", "user_id"),
    "endpoint": ("endpoint", "route", "path"),
    "environment": ("environment", "env"),
    "workflow": ("workflow", "workflowId", "workflow_id"),
    "tool": ("tool", "toolName", "tool_name"),
}

_secret_scanner = SecretScanner()


def mask_secrets(text: str) -> str:
    """Replace every secret span detected by :class:`SecretScanner` with ``***``."""
    matches = _secret_scanner.scan(text, ScanContext())
    if not matches:
        return text
    # Merge overlapping spans (several patterns can match the same key), then
    # replace right-to-left so earlier offsets stay valid.
    spans: list[tuple[int, int]] = []
    for m in sorted(matches, key=lambda m: m.start):
        if spans and m.start < spans[-1][1]:
            spans[-1] = (spans[-1][0], max(spans[-1][1], m.end))
        else:
            spans.append((m.start, m.end))
    for start, end in reversed(spans):
        text = text[:start] + "***" + text[end:]
    return text


def envelope(trace_id: str, type_: str, t_ns: int, seq: int, data: dict[str, Any]) -> dict:
    """Wrap event data in the InspectorEvent envelope."""
    return {
        "schemaVersion": SCHEMA_VERSION,
        "eventId": str(uuid7()),
        "traceId": trace_id,
        "type": type_,
        "tNs": t_ns,
        "seq": seq,
        "data": data,
    }


# ---- trace.start -------------------------------------------------------------


def trace_start_data(
    *,
    provider: str,
    model: str,
    wall_time_utc: str,
    mode: str,
    parent_trace_id: str | None,
    agent_id: str | None,
    session_id: str | None,
    cost_dimensions: dict[str, str] | None = None,
) -> dict[str, Any]:
    """trace.start data — metadata shape (no content parameters)."""
    data: dict[str, Any] = {
        "parentTraceId": parent_trace_id,
        "agentId": agent_id,
        "sessionId": session_id,
        "provider": provider,
        "model": model,
        "wallTimeUtc": wall_time_utc,
        "mode": mode,
    }
    if cost_dimensions:
        data["costDimensions"] = dict(cost_dimensions)
    return data


def trace_start_data_with_content(
    *,
    provider: str,
    model: str,
    wall_time_utc: str,
    mode: str,
    parent_trace_id: str | None,
    agent_id: str | None,
    session_id: str | None,
    messages: list[Message],
    cost_dimensions: dict[str, str] | None = None,
) -> dict[str, Any]:
    """trace.start data — full/redacted shape, request messages included."""
    data = trace_start_data(
        provider=provider,
        model=model,
        wall_time_utc=wall_time_utc,
        mode=mode,
        parent_trace_id=parent_trace_id,
        agent_id=agent_id,
        session_id=session_id,
        cost_dimensions=cost_dimensions,
    )
    data["messages"] = [
        {"role": m.get("role", ""), "content": mask_secrets(m.get("content", ""))} for m in messages
    ]
    return data


# ---- interceptor.{before,after}.{start,end} -----------------------------------


def interceptor_start_data(name: str) -> dict[str, Any]:
    return {"name": name}


def interceptor_end_data(
    name: str,
    duration_us: int,
    mutated: bool,
    decision: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """interceptor.*.end data — metadata shape (no diff parameter)."""
    data: dict[str, Any] = {"name": name, "durationUs": duration_us, "mutated": mutated}
    if decision:
        data["decision"] = decision
    return data


def interceptor_end_data_with_diff(
    name: str,
    duration_us: int,
    mutated: bool,
    decision: dict[str, Any] | None = None,
    diff: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """interceptor.*.end data — full/redacted shape with a mutation diff."""
    data = interceptor_end_data(name, duration_us, mutated, decision)
    if diff:
        data["diff"] = diff
    return data


def request_diff(
    old_messages: list[Message],
    new_messages: list[Message],
    old_model: str,
    new_model: str,
    include_from: bool,
) -> dict[str, Any]:
    """Diff two request snapshots. ``include_from`` is True only in full mode."""
    diff: dict[str, Any] = {}
    changed: list[dict[str, Any]] = []
    for index in range(max(len(old_messages), len(new_messages))):
        old = old_messages[index].get("content", "") if index < len(old_messages) else ""
        new = new_messages[index].get("content", "") if index < len(new_messages) else ""
        if old != new:
            entry: dict[str, Any] = {"index": index}
            if include_from:
                entry["from"] = mask_secrets(old)
            entry["to"] = mask_secrets(new)
            changed.append(entry)
    if changed:
        diff["messages"] = changed
    if old_model != new_model:
        model: dict[str, Any] = {}
        if include_from:
            model["from"] = old_model
        model["to"] = new_model
        diff["model"] = model
    return diff


def content_diff(old_content: str, new_content: str, include_from: bool) -> dict[str, Any]:
    """Diff two response contents (post-interceptor mutation)."""
    entry: dict[str, Any] = {}
    if include_from:
        entry["from"] = mask_secrets(old_content)
    entry["to"] = mask_secrets(new_content)
    return {"content": entry}


# ---- provider.call.{start,end} -------------------------------------------------


def provider_call_start_data(provider: str, model: str, attempt: int) -> dict[str, Any]:
    return {"provider": provider, "model": model, "attempt": attempt}


def provider_call_end_data(
    duration_us: int,
    status: str,
    attempt: int | None = None,
    error_type: str | None = None,
    model_version: str | None = None,
    usage: TokenUsage | None = None,
    cost_usd: float | None = None,
) -> dict[str, Any]:
    data: dict[str, Any] = {"durationUs": duration_us, "status": status}
    if attempt is not None:
        data["attempt"] = attempt
    if error_type:
        data["errorType"] = error_type
    if model_version:
        data["modelVersion"] = model_version
    if usage is not None:
        data["usage"] = {
            "promptTokens": usage.prompt_tokens,
            "completionTokens": usage.completion_tokens,
            "totalTokens": usage.total_tokens,
        }
    if cost_usd is not None:
        data["costUsd"] = cost_usd
    return data


# ---- trace.end / trace.error ----------------------------------------------------


def trace_end_data(
    *,
    status: str,
    latency_ms: int,
    interceptors_fired: list[str],
    cost_usd: float | None = None,
    cache_savings_usd: float | None = None,
    cache_hit: bool | None = None,
    cache_type: str | None = None,
    pii_entity_types: list[str] | None = None,
) -> dict[str, Any]:
    """trace.end data — metadata shape (no content parameter)."""
    data: dict[str, Any] = {
        "status": status,
        "latencyMs": latency_ms,
        "interceptorsFired": list(interceptors_fired),
    }
    if cost_usd is not None:
        data["costUsd"] = cost_usd
    if cache_savings_usd is not None:
        data["cacheSavingsUsd"] = cache_savings_usd
    if cache_hit is not None:
        data["cacheHit"] = cache_hit
        data["cacheType"] = cache_type
    if pii_entity_types:
        data["piiEntityTypes"] = list(pii_entity_types)
    return data


def trace_end_data_with_content(
    *,
    status: str,
    latency_ms: int,
    interceptors_fired: list[str],
    cost_usd: float | None = None,
    cache_savings_usd: float | None = None,
    cache_hit: bool | None = None,
    cache_type: str | None = None,
    pii_entity_types: list[str] | None = None,
    content: str,
) -> dict[str, Any]:
    """trace.end data — full/redacted shape, response content included."""
    data = trace_end_data(
        status=status,
        latency_ms=latency_ms,
        interceptors_fired=interceptors_fired,
        cost_usd=cost_usd,
        cache_savings_usd=cache_savings_usd,
        cache_hit=cache_hit,
        cache_type=cache_type,
        pii_entity_types=pii_entity_types,
    )
    data["content"] = mask_secrets(content)
    return data


def cost_dimensions_from_metadata(metadata: dict[str, Any] | None) -> dict[str, str]:
    """Extract canonical cost attribution dimensions from request metadata."""
    metadata = metadata or {}
    nested = _object(metadata.get("costDimensions")) or _object(metadata.get("cost_dimensions"))
    dimensions: dict[str, str] = {}
    for key in COST_DIMENSION_KEYS:
        value = _first_scalar(nested, COST_DIMENSION_ALIASES[key]) or _first_scalar(
            metadata, COST_DIMENSION_ALIASES[key]
        )
        if value is not None:
            dimensions[key] = value
    return dimensions


def _object(value: Any) -> dict[str, Any] | None:
    return value if isinstance(value, dict) else None


def _first_scalar(source: dict[str, Any] | None, aliases: tuple[str, ...]) -> str | None:
    if source is None:
        return None
    for alias in aliases:
        value = source.get(alias)
        if isinstance(value, str):
            stripped = value.strip()
            if stripped:
                return stripped
        elif isinstance(value, (int, float, bool)):
            return str(value)
    return None


def trace_error_data(
    *,
    origin: str,
    error_type: str,
    message: str,
    handled: bool = False,
    interceptor_name: str | None = None,
) -> dict[str, Any]:
    data: dict[str, Any] = {
        "origin": origin,
        "errorType": error_type,
        "message": message,
        "handled": handled,
    }
    if interceptor_name:
        data["interceptorName"] = interceptor_name
    return data


# ---- governance.event --------------------------------------------------------


def governance_event_data(
    *,
    kind: str,
    detector: str | None = None,
    metric: str | None = None,
    value: float | None = None,
    baseline: dict[str, Any] | None = None,
    z: float | None = None,
    threshold: float | None = None,
) -> dict[str, Any]:
    """Governance signal (drift detection, F-GOV-07). Metadata only — no content."""
    data: dict[str, Any] = {"kind": kind}
    if detector is not None:
        data["detector"] = detector
    if metric is not None:
        data["metric"] = metric
    if value is not None:
        data["value"] = value
    if baseline is not None:
        data["baseline"] = baseline
    if z is not None:
        data["z"] = z
    if threshold is not None:
        data["threshold"] = threshold
    return data
