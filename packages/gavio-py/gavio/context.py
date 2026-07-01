"""Per-request context passed through the interceptor pipeline."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class InterceptorContext:
    """Mutable scratch space shared by all interceptors within one request.

    One instance per request — never shared across requests or threads.
    Interceptors stash signals here (PII findings, cache decisions, risk
    scores) for the audit interceptor to collect at the end of the chain.
    """

    trace_id: str
    agent_id: str | None = None
    parent_trace_id: str | None = None
    session_id: str | None = None
    dry_run: bool = False

    # Signals accumulated by interceptors during the request.
    interceptors_fired: list[str] = field(default_factory=list)
    pii_entity_types: list[str] = field(default_factory=list)
    pii_entity_counts: dict[str, int] = field(default_factory=dict)
    cache_hit: bool = False
    cache_type: str | None = None
    risk_score: float | None = None
    guardrail_outcome: str | None = None

    # Arbitrary inter-interceptor state (e.g. PII replacement map for restore).
    state: dict[str, Any] = field(default_factory=dict)

    def mark_fired(self, name: str) -> None:
        if name not in self.interceptors_fired:
            self.interceptors_fired.append(name)

    def record_pii(self, entity_types: list[str]) -> None:
        for et in entity_types:
            self.pii_entity_counts[et] = self.pii_entity_counts.get(et, 0) + 1
            if et not in self.pii_entity_types:
                self.pii_entity_types.append(et)
