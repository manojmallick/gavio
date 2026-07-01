"""GavioResponse — the canonical response returned to the caller."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from .types import CacheType, TokenUsage

if TYPE_CHECKING:
    from .interceptors.audit.record import AuditRecord


@dataclass
class GavioResponse:
    """Result of a gateway call, enriched by the post-interceptor pipeline."""

    trace_id: str
    content: str
    model: str
    provider: str
    model_version: str = ""
    usage: TokenUsage = field(default_factory=TokenUsage)
    cost_usd: float = 0.0
    latency_ms: int = 0
    cache_hit: bool = False
    cache_type: CacheType | None = None
    interceptors_fired: list[str] = field(default_factory=list)
    audit: AuditRecord | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def copy_with_content(self, content: str) -> GavioResponse:
        """Return a copy with replaced content (used by PII restore, guardrails)."""
        clone = GavioResponse(
            trace_id=self.trace_id,
            content=content,
            model=self.model,
            provider=self.provider,
            model_version=self.model_version,
            usage=self.usage,
            cost_usd=self.cost_usd,
            latency_ms=self.latency_ms,
            cache_hit=self.cache_hit,
            cache_type=self.cache_type,
            interceptors_fired=list(self.interceptors_fired),
            audit=self.audit,
            metadata=dict(self.metadata),
        )
        return clone
