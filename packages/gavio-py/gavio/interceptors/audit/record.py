"""AuditRecord — the immutable, per-request audit entry."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone

from ...types import PromptLineage, TokenUsage

SCHEMA_VERSION = "1.0"


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


@dataclass
class AuditRecord:
    """One append-only audit entry. Carries metadata only — never raw content.

    ``prompt_hash`` / ``response_hash`` are SHA-256 digests so the entry is
    verifiable without storing sensitive text. ``previous_hash`` is reserved
    for the v0.2.0 hash-chain (F-OBS-02); empty in v0.1.0.
    """

    trace_id: str
    provider: str
    model: str
    timestamp_utc: str
    parent_trace_id: str | None = None
    agent_id: str | None = None
    session_id: str | None = None
    model_version: str = ""
    prompt_hash: str = ""
    response_hash: str = ""
    token_usage: TokenUsage = field(default_factory=TokenUsage)
    cost_usd: float = 0.0
    latency_ms: int = 0
    pii_entity_types: list[str] = field(default_factory=list)
    pii_entity_counts: dict[str, int] = field(default_factory=dict)
    interceptors_fired: list[str] = field(default_factory=list)
    cache_hit: bool = False
    cache_type: str | None = None
    guardrail_outcome: str | None = None
    risk_score: float | None = None
    lineage: PromptLineage | None = None
    previous_hash: str = ""
    schema_version: str = SCHEMA_VERSION

    @staticmethod
    def now_utc() -> str:
        return datetime.now(timezone.utc).isoformat()

    @staticmethod
    def hash_text(text: str) -> str:
        return _sha256(text)

    def to_dict(self) -> dict:
        data = asdict(self)
        data["token_usage"] = self.token_usage.to_dict()
        return data

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), sort_keys=True)

    def content_hash(self) -> str:
        """Hash of this record's content — used to build the v0.2.0 chain."""
        return _sha256(self.to_json())
