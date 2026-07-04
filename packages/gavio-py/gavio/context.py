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

    # Decision records attached via inspect(); drained per hook by the
    # inspector emitter (F-DX-09). Harmless when the inspector is off.
    inspect_pending: dict[str, Any] = field(default_factory=dict)

    # Governance events (e.g. drift alerts, F-GOV-07) to surface as standalone
    # governance.event inspector events; drained per hook by the emitter.
    governance_pending: list[dict[str, Any]] = field(default_factory=list)

    def inspect(self, key: str, value: Any) -> None:
        """Attach a JSON-safe decision record for the Gavio Inspector.

        Recorded entries surface in the ``decision`` field of the current
        hook's ``interceptor.*.end`` event. Safe to call unconditionally —
        with the inspector disabled this is just a dict write.
        """
        self.inspect_pending[key] = value

    def drain_inspect(self) -> dict[str, Any]:
        """Return and clear the pending decision records (emitter-internal)."""
        pending, self.inspect_pending = self.inspect_pending, {}
        return pending

    def record_governance_event(self, data: dict[str, Any]) -> None:
        """Queue a governance event (e.g. a drift alert) for the inspector.

        Surfaces as a standalone ``governance.event``. Safe to call
        unconditionally — with the inspector disabled it is just a list append.
        """
        self.governance_pending.append(data)

    def drain_governance(self) -> list[dict[str, Any]]:
        """Return and clear the pending governance events (emitter-internal)."""
        pending, self.governance_pending = self.governance_pending, []
        return pending

    def mark_fired(self, name: str) -> None:
        if name not in self.interceptors_fired:
            self.interceptors_fired.append(name)

    def record_pii(self, entity_types: list[str]) -> None:
        for et in entity_types:
            self.pii_entity_counts[et] = self.pii_entity_counts.get(et, 0) + 1
            if et not in self.pii_entity_types:
                self.pii_entity_types.append(et)
