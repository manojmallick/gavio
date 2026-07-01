"""GavioRequest — the canonical, provider-agnostic request model."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ._ids import new_trace_id
from .types import Message, Provider


@dataclass
class GavioRequest:
    """A single gateway call.

    A ``trace_id`` (UUID v7, time-sortable) is assigned automatically if not
    supplied. ``parent_trace_id`` links calls into a multi-agent DAG.
    """

    messages: list[Message]
    model: str
    provider: Provider
    trace_id: str = field(default_factory=new_trace_id)
    agent_id: str | None = None
    parent_trace_id: str | None = None
    session_id: str | None = None
    options: dict[str, Any] = field(default_factory=dict)
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.provider = Provider.coerce(self.provider)

    @property
    def temperature(self) -> float:
        return float(self.options.get("temperature", 0.7))

    @property
    def max_tokens(self) -> int:
        return int(self.options.get("max_tokens", 1024))

    def prompt_text(self) -> str:
        """Concatenate message contents — used for hashing and token estimation."""
        return "\n".join(m.get("content", "") for m in self.messages)

    def copy_with_messages(self, messages: list[Message]) -> GavioRequest:
        """Return a shallow copy with replaced messages (interceptors mutate via this)."""
        return GavioRequest(
            messages=messages,
            model=self.model,
            provider=self.provider,
            trace_id=self.trace_id,
            agent_id=self.agent_id,
            parent_trace_id=self.parent_trace_id,
            session_id=self.session_id,
            options=dict(self.options),
            metadata=dict(self.metadata),
        )
