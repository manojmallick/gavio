"""ScanContext — per-request state shared across PII scanners."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class ScanContext:
    """Context threaded through every scanner for one request.

    Tracks a monotonic per-entity-type index so repeated entities get stable,
    distinct placeholders (``[EMAIL_1]``, ``[EMAIL_2]``) and the original value
    map needed to restore them in the response.
    """

    language: str = "en"
    locale: str = "NL"
    _counters: dict[str, int] = field(default_factory=dict)

    def next_index(self, entity_type: str) -> int:
        """Return the next 1-based index for an entity type."""
        self._counters[entity_type] = self._counters.get(entity_type, 0) + 1
        return self._counters[entity_type]
