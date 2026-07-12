"""OpenTelemetry-style runtime span export.

This module intentionally stays dependency-light. It maps Gavio runtime events
to a stable OTel-shaped JSON span model that can be written as JSONL or bridged
to a real OpenTelemetry SDK by the application.
"""

from __future__ import annotations

import hashlib
import json
import threading
from collections import defaultdict
from collections.abc import Callable
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, TextIO

from .base import GavioRuntimeExporter, metadata_only_event

SpanWriter = Callable[[dict[str, Any]], None]


class OtelSpanExporter(GavioRuntimeExporter):
    """Export runtime events as OpenTelemetry-style span JSON lines.

    Pass ``write`` to receive span dictionaries in-process, ``stream`` in tests,
    or ``path`` in applications. Export is metadata-only by default, matching
    the JSONL runtime exporter privacy boundary.
    """

    def __init__(
        self,
        path: str | Path | None = None,
        *,
        stream: TextIO | None = None,
        write: SpanWriter | None = None,
        service_name: str = "gavio",
        metadata_only: bool = True,
    ) -> None:
        destinations = sum(1 for value in (path, stream, write) if value is not None)
        if destinations == 0:
            raise ValueError("OtelSpanExporter requires path, stream, or write")
        if destinations > 1:
            raise ValueError("pass only one of path, stream, or write")
        self.path = Path(path).expanduser() if path is not None else None
        if self.path is not None:
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self._stream = stream
        self._write = write
        self._metadata_only = metadata_only
        self._mapper = OtelSpanMapper(service_name=service_name)
        self._lock = threading.Lock()

    def export_event(self, event: dict[str, Any]) -> None:
        payload = metadata_only_event(event) if self._metadata_only else deepcopy(event)
        for span in self._mapper.consume(payload):
            self._write_span(span)

    def _write_span(self, span: dict[str, Any]) -> None:
        with self._lock:
            if self._write is not None:
                self._write(span)
                return
            line = json.dumps(span, separators=(",", ":"), sort_keys=True) + "\n"
            if self._stream is not None:
                self._stream.write(line)
                return
            assert self.path is not None
            with self.path.open("a", encoding="utf-8") as f:
                f.write(line)

    def flush(self) -> None:
        if self._stream is not None:
            self._stream.flush()


class OtelSpanMapper:
    """Stateful converter from Inspector/Gavio runtime events to span JSON."""

    def __init__(self, *, service_name: str = "gavio") -> None:
        self.service_name = service_name
        self._traces: dict[str, _TraceState] = {}

    def consume(self, event: dict[str, Any]) -> list[dict[str, Any]]:
        event_type = str(event.get("type", ""))
        trace_id = str(event.get("traceId", ""))
        if not trace_id:
            return []
        if event_type == "trace.start":
            self._traces[trace_id] = _TraceState.from_start(event, self.service_name)
            return []
        state = self._traces.get(trace_id)
        if state is None:
            return []
        if event_type.endswith(".start"):
            state.open_span(event)
            return []
        if event_type == "interceptor.before.end" or event_type == "interceptor.after.end":
            return state.close_interceptor(event)
        if event_type == "provider.call.end":
            return state.close_provider(event)
        if event_type == "trace.error":
            state.add_exception(event)
            return []
        if event_type == "governance.event":
            state.add_event("gavio.governance", event, dict(event.get("data") or {}))
            return []
        if event_type == "trace.end":
            span = state.close_root(event)
            self._traces.pop(trace_id, None)
            return [span]
        return []


def otel_spans_from_events(
    events: list[dict[str, Any]], *, service_name: str = "gavio", metadata_only: bool = True
) -> list[dict[str, Any]]:
    """Map a list of runtime events to OTel-style span JSON."""

    mapper = OtelSpanMapper(service_name=service_name)
    spans: list[dict[str, Any]] = []
    for event in events:
        payload = metadata_only_event(event) if metadata_only else deepcopy(event)
        spans.extend(mapper.consume(payload))
    return spans


class _TraceState:
    def __init__(self, start_event: dict[str, Any], service_name: str) -> None:
        self.start_event = start_event
        self.start_data = dict(start_event.get("data") or {})
        self.service_name = service_name
        self.original_trace_id = str(start_event.get("traceId"))
        self.otel_trace_id = _hex_id(self.original_trace_id, 32)
        self.root_span_id = _hex_id(f"{self.original_trace_id}:root", 16)
        self.root_start_ns = _wall_time_ns(self.start_data.get("wallTimeUtc"))
        self._open: dict[str, list[dict[str, Any]]] = defaultdict(list)
        self._root_events: list[dict[str, Any]] = []

    @classmethod
    def from_start(cls, event: dict[str, Any], service_name: str) -> _TraceState:
        return cls(event, service_name)

    def open_span(self, event: dict[str, Any]) -> None:
        self._open[_open_key(event)].append(event)

    def close_interceptor(self, end_event: dict[str, Any]) -> list[dict[str, Any]]:
        phase = "before" if end_event.get("type") == "interceptor.before.end" else "after"
        data = dict(end_event.get("data") or {})
        start_event = self._pop_open(f"interceptor.{phase}", data.get("name"))
        if start_event is None:
            return []
        name = str(data.get("name", "unknown"))
        attributes = self._base_attributes()
        attributes.update(
            {
                "gavio.interceptor.name": name,
                "gavio.interceptor.phase": phase,
                "gavio.interceptor.mutated": bool(data.get("mutated", False)),
            }
        )
        if "durationUs" in data:
            attributes["gavio.duration_us"] = data["durationUs"]
        decision = data.get("decision")
        if isinstance(decision, dict):
            for key, value in _flatten("gavio.decision", decision).items():
                attributes[key] = value
        return [
            self._span(
                name=f"gavio.interceptor.{phase} {name}",
                logical_key=f"interceptor.{phase}:{name}:{start_event.get('seq')}",
                start_event=start_event,
                end_event=end_event,
                attributes=attributes,
                error=False,
            )
        ]

    def close_provider(self, end_event: dict[str, Any]) -> list[dict[str, Any]]:
        end_data = dict(end_event.get("data") or {})
        start_event = self._pop_open("provider.call", end_data.get("attempt"))
        if start_event is None:
            return []
        start_data = dict(start_event.get("data") or {})
        model = str(start_data.get("model") or self.start_data.get("model") or "unknown")
        provider = str(start_data.get("provider") or self.start_data.get("provider") or "unknown")
        status = str(end_data.get("status") or "ok")
        attributes = self._base_attributes()
        attributes.update(
            {
                "gen_ai.system": provider,
                "gen_ai.request.model": model,
            }
        )
        if "modelVersion" in end_data:
            attributes["gen_ai.response.model"] = end_data["modelVersion"]
        if "attempt" in end_data:
            attributes["gavio.retry.attempt"] = end_data["attempt"]
        usage = end_data.get("usage")
        if isinstance(usage, dict):
            _copy_if_present(attributes, usage, "promptTokens", "gen_ai.usage.input_tokens")
            _copy_if_present(attributes, usage, "completionTokens", "gen_ai.usage.output_tokens")
            _copy_if_present(attributes, usage, "totalTokens", "gen_ai.usage.total_tokens")
        _copy_if_present(attributes, end_data, "costUsd", "gen_ai.usage.cost")
        _copy_if_present(attributes, end_data, "durationUs", "gavio.duration_us")
        _copy_if_present(attributes, end_data, "errorType", "error.type")
        return [
            self._span(
                name=f"chat {model}",
                logical_key=f"provider:{end_data.get('attempt', start_event.get('seq'))}",
                start_event=start_event,
                end_event=end_event,
                attributes=attributes,
                error=status != "ok",
                status_message=str(end_data.get("errorType", "")) or None,
            )
        ]

    def add_exception(self, event: dict[str, Any]) -> None:
        data = dict(event.get("data") or {})
        attrs = {
            "exception.type": data.get("errorType", "Error"),
            "exception.message": data.get("message", ""),
            "gavio.error.origin": data.get("origin", "chain"),
            "exception.escaped": not bool(data.get("handled", False)),
        }
        if "interceptorName" in data:
            attrs["gavio.interceptor.name"] = data["interceptorName"]
        self.add_event("exception", event, attrs)

    def add_event(self, name: str, event: dict[str, Any], attributes: dict[str, Any]) -> None:
        self._root_events.append(
            {
                "name": name,
                "timeUnixNano": self._time_ns(event),
                "attributes": _clean(attributes),
            }
        )

    def close_root(self, end_event: dict[str, Any]) -> dict[str, Any]:
        start_data = self.start_data
        end_data = dict(end_event.get("data") or {})
        attributes = self._base_attributes()
        _copy_if_present(attributes, start_data, "agentId", "gavio.agent_id")
        _copy_if_present(attributes, start_data, "sessionId", "session.id")
        _copy_if_present(attributes, start_data, "parentTraceId", "gavio.parent_trace_id")
        _copy_if_present(attributes, start_data, "provider", "gen_ai.system")
        _copy_if_present(attributes, start_data, "model", "gen_ai.request.model")
        _copy_if_present(attributes, end_data, "latencyMs", "gavio.latency_ms")
        _copy_if_present(attributes, end_data, "costUsd", "gen_ai.usage.cost")
        _copy_if_present(attributes, end_data, "cacheHit", "gavio.cache.hit")
        _copy_if_present(attributes, end_data, "cacheType", "gavio.cache.type")
        _copy_if_present(attributes, end_data, "piiEntityTypes", "gavio.pii.entity_types")
        _copy_if_present(attributes, end_data, "interceptorsFired", "gavio.interceptors")
        dimensions = start_data.get("costDimensions")
        if isinstance(dimensions, dict):
            for key, value in dimensions.items():
                attributes[f"gavio.cost.dimension.{key}"] = value
        return self._span(
            name="gavio.request",
            logical_key="root",
            start_event=self.start_event,
            end_event=end_event,
            attributes=attributes,
            error=str(end_data.get("status", "ok")) != "ok",
            status_message=str(end_data.get("status", "")) or None,
            parent_span_id=None,
            span_id=self.root_span_id,
            events=list(self._root_events),
        )

    def _span(
        self,
        *,
        name: str,
        logical_key: str,
        start_event: dict[str, Any],
        end_event: dict[str, Any],
        attributes: dict[str, Any],
        error: bool,
        status_message: str | None = None,
        parent_span_id: str | None = "root",
        span_id: str | None = None,
        events: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        resolved_parent = (
            None
            if parent_span_id is None
            else self.root_span_id
            if parent_span_id == "root"
            else parent_span_id
        )
        status: dict[str, Any] = {"code": "ERROR" if error else "OK"}
        if error and status_message:
            status["message"] = status_message
        return {
            "traceId": self.otel_trace_id,
            "spanId": span_id or _hex_id(f"{self.original_trace_id}:{logical_key}", 16),
            "parentSpanId": resolved_parent,
            "name": name,
            "kind": "INTERNAL",
            "startTimeUnixNano": self._time_ns(start_event),
            "endTimeUnixNano": self._time_ns(end_event),
            "status": status,
            "attributes": _clean(attributes),
            "events": events or [],
        }

    def _base_attributes(self) -> dict[str, Any]:
        return {
            "service.name": self.service_name,
            "gavio.trace_id": self.original_trace_id,
            "gavio.event.schema_version": self.start_event.get("schemaVersion", "1.0"),
        }

    def _pop_open(self, family: str, discriminator: Any) -> dict[str, Any] | None:
        key = f"{family}:{discriminator}"
        if self._open.get(key):
            return self._open[key].pop()
        fallback = next(
            (k for k in self._open if k.startswith(f"{family}:") and self._open[k]),
            None,
        )
        if fallback is None:
            return None
        return self._open[fallback].pop()

    def _time_ns(self, event: dict[str, Any]) -> int:
        return self.root_start_ns + int(event.get("tNs") or 0)


def _open_key(event: dict[str, Any]) -> str:
    data = dict(event.get("data") or {})
    event_type = str(event.get("type"))
    if event_type.startswith("interceptor."):
        phase = "before" if event_type == "interceptor.before.start" else "after"
        return f"interceptor.{phase}:{data.get('name')}"
    if event_type == "provider.call.start":
        return f"provider.call:{data.get('attempt')}"
    return f"{event_type}:{event.get('seq')}"


def _wall_time_ns(value: Any) -> int:
    if not isinstance(value, str) or not value:
        return 0
    text = value.replace("Z", "+00:00")
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return 0
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp() * 1_000_000_000)


def _hex_id(seed: str, length: int) -> str:
    return hashlib.sha256(seed.encode("utf-8")).hexdigest()[:length]


def _flatten(prefix: str, value: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for key, raw in value.items():
        name = f"{prefix}.{key}"
        if isinstance(raw, dict):
            out.update(_flatten(name, raw))
        else:
            out[name] = raw
    return out


def _copy_if_present(
    target: dict[str, Any], source: dict[str, Any], source_key: str, target_key: str
) -> None:
    value = source.get(source_key)
    if value is not None:
        target[target_key] = value


def _clean(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: _clean(v) for k, v in value.items() if v is not None}
    if isinstance(value, list):
        return [_clean(item) for item in value]
    return value
