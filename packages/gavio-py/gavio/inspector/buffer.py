"""RingBuffer — bounded in-memory trace store fed by the InspectorBus."""

from __future__ import annotations

import threading
from collections import OrderedDict
from typing import Any

MAX_EVENTS_PER_TRACE = 500

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
                    "summary": {"traceId": trace_id, "status": "running"},
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
        elif event["type"] == "trace.end":
            for key in _SUMMARY_END_KEYS:
                if key in data:
                    summary[key] = data[key]

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
