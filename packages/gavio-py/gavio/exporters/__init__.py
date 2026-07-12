"""Runtime event exporters for Gavio.

Exporters subscribe to the same metadata-safe Inspector event stream that powers
the local Inspector UI. The default JSONL exporter strips content-bearing fields
(``messages``, ``content``, and ``diff``) before writing, even if the Inspector
itself is running in a content-capturing mode.
"""

from .base import GavioRuntimeExporter, metadata_only_event
from .jsonl import JsonlRuntimeExporter
from .otel import OtelSpanExporter, otel_spans_from_events

__all__ = [
    "GavioRuntimeExporter",
    "JsonlRuntimeExporter",
    "OtelSpanExporter",
    "metadata_only_event",
    "otel_spans_from_events",
]
