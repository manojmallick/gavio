"""Runtime exporter contracts.

The public 1.1.0 exporter surface intentionally reuses InspectorEvent as the
runtime event envelope. That keeps Inspector, OTel export, and JSONL export on
one event path instead of creating parallel observability models.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from copy import deepcopy
from typing import Any

CONTENT_KEYS = frozenset({"messages", "content", "diff"})


class GavioRuntimeExporter(ABC):
    """Synchronous runtime event exporter.

    Exporters are called by the InspectorBus on the request path. They should do
    small, bounded work and must not depend on raw prompt/response content being
    present.
    """

    @abstractmethod
    def export_event(self, event: dict[str, Any]) -> None:
        """Export one InspectorEvent/Gavio runtime event."""

    def flush(self) -> None:
        """Flush buffered exporter state, if any."""
        return None

    def close(self) -> None:
        """Close exporter resources, if any."""
        self.flush()


def metadata_only_event(event: dict[str, Any]) -> dict[str, Any]:
    """Return a deep copy with content-bearing event data removed.

    This is the default privacy boundary for runtime export. It strips only the
    fields that can carry prompt/response text while preserving decisions,
    timings, costs, model/provider metadata, and trace identifiers.
    """

    out = deepcopy(event)
    data = out.get("data")
    if isinstance(data, dict):
        _strip_content(data)
    return out


def _strip_content(value: Any) -> None:
    if isinstance(value, dict):
        for key in list(value.keys()):
            if key in CONTENT_KEYS:
                value.pop(key, None)
                continue
            _strip_content(value[key])
    elif isinstance(value, list):
        for item in value:
            _strip_content(item)
