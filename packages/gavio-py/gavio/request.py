"""GavioRequest — the canonical, provider-agnostic request model."""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from typing import Any

from ._ids import new_trace_id
from .tool_calling import message_text, message_tool_calls
from .types import Message, PromptLineage, Provider, canonical_json


@dataclass
class GavioRequest:
    """A single gateway call.

    A ``trace_id`` (UUID v7, time-sortable) is assigned automatically if not
    supplied. ``parent_trace_id`` links calls into a multi-agent DAG.

    ``tools`` declares the tools the model may call (``ToolDefinition`` dicts) and
    ``tool_choice`` steers it: ``"auto"``, ``"none"``, ``"required"`` or
    ``{"name": <tool>}`` (F-ADP-03).
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
    # Binary image inputs, scanned for PII before a provider call (F-SEC-09).
    images: list[bytes] = field(default_factory=list)
    lineage: PromptLineage | None = None
    tools: list[dict[str, Any]] | None = None
    tool_choice: str | dict[str, Any] | None = None

    def __post_init__(self) -> None:
        self.provider = Provider.coerce(self.provider)

    @property
    def temperature(self) -> float:
        return float(self.options.get("temperature", 0.7))

    @property
    def max_tokens(self) -> int:
        return int(self.options.get("max_tokens", 1024))

    def prompt_text(self) -> str:
        """Concatenate message contents — used for hashing and token estimation.

        Tool calls an assistant message made are included as ``name(canonical-args)`` so
        the hash and the estimate see them; messages without tool calls contribute
        exactly their content, as before.
        """
        parts: list[str] = []
        for message in self.messages:
            parts.append(message_text(message))
            parts.extend(
                f"{call.name}({canonical_json(call.arguments)})"
                for call in message_tool_calls(message)
            )
        return "\n".join(parts)

    def copy_with_messages(self, messages: list[Message]) -> GavioRequest:
        """Return a shallow copy with replaced messages (interceptors mutate via this).

        Built with :func:`dataclasses.replace`, so a field added to the request later is
        carried automatically rather than silently dropped.
        """
        return replace(
            self,
            messages=messages,
            options=dict(self.options),
            metadata=dict(self.metadata),
            tools=list(self.tools) if self.tools is not None else None,
            tool_choice=(
                dict(self.tool_choice)
                if isinstance(self.tool_choice, dict)
                else self.tool_choice
            ),
        )
