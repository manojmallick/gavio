"""RingBuffer — bounded in-memory trace store fed by the InspectorBus."""

from __future__ import annotations

import threading
from collections import OrderedDict
from typing import Any

MAX_EVENTS_PER_TRACE = 500
COST_DIMENSION_KEYS = ("feature", "tenant", "user", "endpoint", "environment", "workflow", "tool")

_SUMMARY_START_KEYS = (
    ("parentTraceId", None),
    ("agentId", None),
    ("sessionId", None),
    ("provider", None),
    ("model", None),
    ("wallTimeUtc", None),
)
_SUMMARY_END_KEYS = (
    "status",
    "latencyMs",
    "costUsd",
    "cacheHit",
    "cacheType",
    "piiEntityTypes",
    "interceptorsFired",
)


class RingBuffer:
    """Assembles inspector events into per-trace records, oldest evicted first.

    Each trace is ``{"summary": {...}, "events": [...]}``. The summary is built
    from trace.start and trace.end data; events are capped at
    :data:`MAX_EVENTS_PER_TRACE` per trace. Thread-safe — the HTTP server reads
    from other threads.
    """

    def __init__(self, max_traces: int = 1000) -> None:
        self._max_traces = max_traces
        self._traces: OrderedDict[str, dict[str, Any]] = OrderedDict()
        self._lock = threading.Lock()

    def on_event(self, event: dict[str, Any]) -> None:
        """Bus subscriber — append the event to its trace."""
        trace_id = event["traceId"]
        with self._lock:
            trace = self._traces.get(trace_id)
            if trace is None:
                while len(self._traces) >= self._max_traces:
                    self._traces.popitem(last=False)
                trace = {
                    "summary": _with_cost_defaults({"traceId": trace_id, "status": "running"}),
                    "events": [],
                }
                self._traces[trace_id] = trace
            if len(trace["events"]) < MAX_EVENTS_PER_TRACE:
                trace["events"].append(event)
            self._update_summary(trace["summary"], event)

    @staticmethod
    def _update_summary(summary: dict[str, Any], event: dict[str, Any]) -> None:
        data = event["data"]
        if event["type"] == "trace.start":
            for key, default in _SUMMARY_START_KEYS:
                summary[key] = data.get(key, default)
            dimensions = _cost_dimensions(data.get("costDimensions"))
            summary["costDimensions"] = dimensions
            for key in COST_DIMENSION_KEYS:
                summary[key] = dimensions.get(key)
        elif event["type"] == "trace.end":
            for key in _SUMMARY_END_KEYS:
                if key in data:
                    summary[key] = data[key]
            fired = summary.get("interceptorsFired") or []
            summary["middlewareChain"] = ">".join(fired) if fired else None
            if "cacheSavingsUsd" in data:
                summary["cacheSavingsUsd"] = data["cacheSavingsUsd"]
        elif event["type"] == "provider.call.start":
            attempt = _number(data.get("attempt")) or (summary.get("providerCallCount", 0) + 1)
            summary["providerCallCount"] = max(summary.get("providerCallCount", 0), attempt)
            summary["retryCount"] = max(0, summary["providerCallCount"] - 1)
        elif event["type"] == "provider.call.end":
            if "usage" in data:
                # Token usage feeds /api/stats and /api/simulate-cost.
                summary["usage"] = data["usage"]
            attempt = _number(data.get("attempt"))
            cost_usd = _number(data.get("costUsd")) or 0
            if attempt is not None and attempt > 1 and cost_usd > 0:
                summary["retryOverheadUsd"] = round(
                    summary.get("retryOverheadUsd", 0.0) + cost_usd,
                    8,
                )
        elif event["type"] == "governance.event" and data.get("kind") == "drift":
            metric = data.get("metric")
            if isinstance(metric, str):
                summary.setdefault("driftAlerts", []).append(metric)

    def seed(self, summary: dict[str, Any], events: list[dict[str, Any]] | None = None) -> None:
        """Insert a pre-assembled trace — store mode loads audit records this way."""
        with self._lock:
            while len(self._traces) >= self._max_traces:
                self._traces.popitem(last=False)
            self._traces[summary["traceId"]] = {
                "summary": _with_cost_defaults(dict(summary)),
                "events": list(events or []),
            }

    def count(self) -> int:
        with self._lock:
            return len(self._traces)

    def summaries(self, limit: int | None = None) -> list[dict[str, Any]]:
        """Trace summaries, chronological ascending. ``limit`` keeps the most recent N."""
        with self._lock:
            out = [dict(t["summary"]) for t in self._traces.values()]
        if limit is not None and limit >= 0:
            out = out[-limit:] if limit else []
        return out

    def get(self, trace_id: str) -> dict[str, Any] | None:
        """Full record for one trace, or None if unknown/evicted."""
        with self._lock:
            trace = self._traces.get(trace_id)
            if trace is None:
                return None
            return {"summary": dict(trace["summary"]), "events": list(trace["events"])}


def _with_cost_defaults(summary: dict[str, Any]) -> dict[str, Any]:
    summary.setdefault("costDimensions", {})
    for key in COST_DIMENSION_KEYS:
        summary.setdefault(key, (summary.get("costDimensions") or {}).get(key))
    summary.setdefault("middlewareChain", None)
    summary.setdefault("providerCallCount", 0)
    summary.setdefault("retryCount", 0)
    summary.setdefault("retryOverheadUsd", 0.0)
    summary.setdefault("cacheSavingsUsd", 0.0)
    return summary


def _cost_dimensions(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        return {}
    return {key: value[key] for key in COST_DIMENSION_KEYS if isinstance(value.get(key), str)}


def _number(value: Any) -> int | float | None:
    return value if isinstance(value, (int, float)) else None
