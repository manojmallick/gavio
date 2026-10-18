"""GavioResponse — the canonical response returned to the caller."""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import TYPE_CHECKING, Any

from .types import CacheType, FinishReason, Message, TokenUsage, ToolCall

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
    # Set on embedding calls (F-SEC-10): one vector per input text.
    embeddings: list[list[float]] | None = None
    # Tool calls the model requested, in provider order (F-ADP-03). ``content`` is
    # empty when the model only called tools.
    tool_calls: list[ToolCall] = field(default_factory=list)
    finish_reason: FinishReason | None = None

    def copy_with(self, **changes: Any) -> GavioResponse:
        """Return a copy with ``changes`` applied (used by PII restore, guardrails).

        Built with :func:`dataclasses.replace`, so a field added to the response later is
        carried automatically rather than silently dropped.
        """
        base: dict[str, Any] = {
            "interceptors_fired": list(self.interceptors_fired),
            "metadata": dict(self.metadata),
            "tool_calls": list(self.tool_calls),
        }
        return replace(self, **{**base, **changes})

    def copy_with_content(self, content: str) -> GavioResponse:
        """Return a copy with replaced content (used by PII restore, guardrails)."""
        return self.copy_with(content=content)

    def to_assistant_message(self) -> Message:
        """The assistant message to append to the conversation before tool results."""
        message: Message = {"role": "assistant", "content": self.content}
        if self.tool_calls:
            message["tool_calls"] = [call.to_dict() for call in self.tool_calls]
        return message
