"""Aggregations over trace summaries — DAG, sessions, stats (F-OBS-10 / F-DX-08).

Pure functions over the summary dicts produced by :class:`RingBuffer` (live
mode) or seeded from audit records (store mode). Shared by both servers so the
JSON shapes stay identical.
"""

from __future__ import annotations

import math
from datetime import datetime
from typing import Any

_GROUP_BY_FIELDS = {
    "provider": "provider",
    "model": "model",
    "agent_id": "agentId",
    "session_id": "sessionId",
    "feature": "feature",
    "tenant": "tenant",
    "user": "user",
    "endpoint": "endpoint",
    "environment": "environment",
    "workflow": "workflow",
    "tool": "tool",
    "middleware_chain": "middlewareChain",
}


def build_sessions(summaries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Group summaries by sessionId — trace counts, cost, duration, agents."""
    sessions: dict[str, dict[str, Any]] = {}
    for s in summaries:
        session_id = s.get("sessionId")
        if not session_id:
            continue
        entry = sessions.setdefault(
            session_id,
            {
                "sessionId": session_id,
                "traces": 0,
                "errors": 0,
                "totalCostUsd": 0.0,
                "totalLatencyMs": 0,
                "agents": [],
                "firstWallTimeUtc": s.get("wallTimeUtc"),
                "lastWallTimeUtc": s.get("wallTimeUtc"),
            },
        )
        entry["traces"] += 1
        if s.get("status") in ("error", "blocked"):
            entry["errors"] += 1
        entry["totalCostUsd"] = round(entry["totalCostUsd"] + (s.get("costUsd") or 0.0), 8)
        entry["totalLatencyMs"] += s.get("latencyMs") or 0
        agent = s.get("agentId")
        if agent and agent not in entry["agents"]:
            entry["agents"].append(agent)
        entry["lastWallTimeUtc"] = s.get("wallTimeUtc") or entry["lastWallTimeUtc"]
    return list(sessions.values())


def build_dag(
    summaries: list[dict[str, Any]],
    root: str | None = None,
    session_id: str | None = None,
) -> dict[str, Any] | None:
    """Agent call graph from parent_trace_id links, with subtree rollups.

    Select nodes by ``session_id`` or by ``root`` trace id (the root plus every
    descendant). Returns None when ``root`` is given but unknown.
    """
    by_id = {s["traceId"]: s for s in summaries}
    children: dict[str, list[str]] = {}
    for s in summaries:
        parent = s.get("parentTraceId")
        if parent:
            children.setdefault(parent, []).append(s["traceId"])

    if session_id is not None:
        selected = [s["traceId"] for s in summaries if s.get("sessionId") == session_id]
    else:
        if root not in by_id:
            return None
        selected = []
        stack, seen = [root], set()
        while stack:
            trace_id = stack.pop()
            if trace_id in seen:
                continue  # defensive: a parent_trace_id cycle must not hang us
            seen.add(trace_id)
            selected.append(trace_id)
            stack.extend(children.get(trace_id, []))

    node_set = set(selected)

    def subtree(trace_id: str, seen: set[str]) -> dict[str, Any]:
        seen.add(trace_id)
        s = by_id[trace_id]
        rollup = {
            "traces": 1,
            "errors": 1 if s.get("status") in ("error", "blocked") else 0,
            "costUsd": s.get("costUsd") or 0.0,
            "latencyMs": s.get("latencyMs") or 0,
        }
        for child in children.get(trace_id, []):
            if child in node_set and child not in seen:
                child_rollup = subtree(child, seen)
                rollup["traces"] += child_rollup["traces"]
                rollup["errors"] += child_rollup["errors"]
                rollup["costUsd"] += child_rollup["costUsd"]
                rollup["latencyMs"] += child_rollup["latencyMs"]
        rollup["costUsd"] = round(rollup["costUsd"], 8)
        return rollup

    nodes = []
    for trace_id in selected:
        s = by_id.get(trace_id)
        if s is None:
            continue
        nodes.append(
            {
                "traceId": trace_id,
                "parentTraceId": s.get("parentTraceId"),
                "agentId": s.get("agentId"),
                "sessionId": s.get("sessionId"),
                "provider": s.get("provider"),
                "model": s.get("model"),
                "status": s.get("status"),
                "latencyMs": s.get("latencyMs"),
                "costUsd": s.get("costUsd"),
                "cacheHit": s.get("cacheHit"),
                "wallTimeUtc": s.get("wallTimeUtc"),
                "subtree": subtree(trace_id, set()),
            }
        )
    edges = [
        {"from": s.get("parentTraceId"), "to": s["traceId"]}
        for s in (by_id[t] for t in selected if t in by_id)
        if s.get("parentTraceId") in node_set
    ]
    return {"nodes": nodes, "edges": edges}


def build_stats(
    summaries: list[dict[str, Any]],
    group_by: str | None = None,
    since: str | None = None,
) -> dict[str, Any]:
    """RED aggregates: rate, errors, latency percentiles, tokens, cost, cache, PII.

    Raises ValueError for an unknown ``group_by`` or an unparsable ``since``.
    """
    summaries = _filter_and_validate(summaries, group_by, since)

    out: dict[str, Any] = {"total": _aggregate(summaries)}
    if group_by is not None:
        groups: dict[str, list[dict[str, Any]]] = {}
        for s in summaries:
            groups.setdefault(_group_key(s, group_by), []).append(s)
        out["groups"] = {key: _aggregate(members) for key, members in groups.items()}
    return out


def build_cost_report(
    summaries: list[dict[str, Any]],
    group_by: str | None = None,
    since: str | None = None,
) -> dict[str, Any]:
    """Cost intelligence rollup: spend, retries, cache savings and top dimensions."""
    summaries = _filter_and_validate(summaries, group_by, since)
    out: dict[str, Any] = {"total": _aggregate(summaries), "topSpend": _top_spend(summaries)}
    if group_by is not None:
        groups: dict[str, list[dict[str, Any]]] = {}
        for s in summaries:
            groups.setdefault(_group_key(s, group_by), []).append(s)
        out["groups"] = {key: _aggregate(members) for key, members in groups.items()}
    return out


def _filter_and_validate(
    summaries: list[dict[str, Any]], group_by: str | None, since: str | None
) -> list[dict[str, Any]]:
    if group_by is not None and group_by not in _GROUP_BY_FIELDS:
        raise ValueError(f"group_by must be one of {sorted(_GROUP_BY_FIELDS)}")
    if since is None:
        return summaries
    since_dt = datetime.fromisoformat(since)
    return [
        s
        for s in summaries
        if s.get("wallTimeUtc") and datetime.fromisoformat(s["wallTimeUtc"]) >= since_dt
    ]


def _group_key(summary: dict[str, Any], group_by: str) -> str:
    field = _GROUP_BY_FIELDS[group_by]
    value = summary.get(field)
    return str(value) if value not in (None, "") else "unknown"


def _aggregate(summaries: list[dict[str, Any]]) -> dict[str, Any]:
    latencies = sorted(s["latencyMs"] for s in summaries if s.get("latencyMs") is not None)
    errors = sum(1 for s in summaries if s.get("status") in ("error", "blocked"))
    cache_hits = sum(1 for s in summaries if s.get("cacheHit"))
    prompt = sum((s.get("usage") or {}).get("promptTokens", 0) for s in summaries)
    completion = sum((s.get("usage") or {}).get("completionTokens", 0) for s in summaries)
    pii: dict[str, int] = {}
    for s in summaries:
        for entity_type in s.get("piiEntityTypes") or []:
            pii[entity_type] = pii.get(entity_type, 0) + 1
    drift: dict[str, int] = {}
    for s in summaries:
        for metric in s.get("driftAlerts") or []:
            drift[metric] = drift.get(metric, 0) + 1
    n = len(summaries)
    cost_usd = round(sum(s.get("costUsd") or 0.0 for s in summaries), 8)
    return {
        "requests": n,
        "errors": errors,
        "errorRate": round(errors / n, 4) if n else 0.0,
        "latencyMs": {
            "p50": _percentile(latencies, 50),
            "p95": _percentile(latencies, 95),
            "p99": _percentile(latencies, 99),
        },
        "tokens": {"prompt": prompt, "completion": completion, "total": prompt + completion},
        "costUsd": cost_usd,
        "averageCostUsd": round(cost_usd / n, 8) if n else 0.0,
        "cacheHits": cache_hits,
        "cacheHitRate": round(cache_hits / n, 4) if n else 0.0,
        "retryCount": sum(s.get("retryCount") or 0 for s in summaries),
        "retryOverheadUsd": round(sum(s.get("retryOverheadUsd") or 0.0 for s in summaries), 8),
        "cacheSavingsUsd": round(sum(s.get("cacheSavingsUsd") or 0.0 for s in summaries), 8),
        "piiDetections": pii,
        "driftAlerts": drift,
    }


def _top_spend(summaries: list[dict[str, Any]]) -> dict[str, list[dict[str, Any]]]:
    out: dict[str, list[dict[str, Any]]] = {}
    for dimension in _GROUP_BY_FIELDS:
        groups: dict[str, dict[str, Any]] = {}
        for s in summaries:
            key = _group_key(s, dimension)
            entry = groups.setdefault(key, {"key": key, "requests": 0, "costUsd": 0.0})
            entry["requests"] += 1
            entry["costUsd"] = round(entry["costUsd"] + (s.get("costUsd") or 0.0), 8)
        out[dimension] = sorted(
            groups.values(),
            key=lambda entry: (-entry["costUsd"], -entry["requests"], entry["key"]),
        )[:5]
    return out


def _percentile(sorted_values: list[int], pct: int) -> int | None:
    """Nearest-rank percentile over an ascending list; None when empty."""
    if not sorted_values:
        return None
    rank = max(1, math.ceil(pct / 100 * len(sorted_values)))
    return sorted_values[rank - 1]
