"""Normalised tool-calling helpers shared by provider adapters and interceptors (F-ADP-03).

Everything here is pure: no network, no clock. Provider adapters use it to translate
between Gavio's normalised tool calls and each provider's native shape; ``PiiGuard`` uses
it to find the strings inside tool-call arguments.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

from .exceptions import ConfigurationError, MalformedToolCallError
from .types import FinishReason, Message, TokenUsage, ToolCall

EMPTY_OBJECT_SCHEMA: dict[str, Any] = {"type": "object", "properties": {}}


# ---- messages -----------------------------------------------------------------------
# Messages are plain dicts. Python spells the tool keys snake_case; the wire spelling
# (camelCase) is accepted on read so spec-shaped JSON can be passed straight in.


def tool_calls_key(message: Mapping[str, Any]) -> str | None:
    """The key under which ``message`` carries tool calls, or None."""
    for key in ("tool_calls", "toolCalls"):
        if key in message:
            return key
    return None


def message_tool_calls(message: Mapping[str, Any]) -> list[ToolCall]:
    """Tool calls carried by an assistant message (empty for every other message)."""
    key = tool_calls_key(message)
    raw = message.get(key) if key else None
    return [ToolCall.from_dict(item) for item in raw or []]


def message_tool_call_id(message: Mapping[str, Any]) -> str | None:
    value = message.get("tool_call_id", message.get("toolCallId"))
    return str(value) if value else None


def message_is_error(message: Mapping[str, Any]) -> bool:
    return bool(message.get("is_error", message.get("isError", False)))


def map_leaves(value: Any, fn: Callable[[Any], Any]) -> Any:
    """Apply ``fn`` to every scalar leaf of a JSON value, keeping its structure.

    Object keys are not visited: they come from the tool's declared schema, not from
    user data.
    """
    if isinstance(value, dict):
        return {key: map_leaves(item, fn) for key, item in value.items()}
    if isinstance(value, list):
        return [map_leaves(item, fn) for item in value]
    return fn(value)


# ---- tool declarations and choice ---------------------------------------------------


@dataclass(frozen=True)
class ToolSpec:
    """The provider-relevant part of a ``ToolDefinition``: name, description, input schema."""

    name: str
    description: str | None = None
    input_schema: dict[str, Any] | None = None


def tool_specs(tools: list[dict[str, Any]] | None) -> list[ToolSpec]:
    """Normalise ``GavioRequest.tools``. Governance fields (permissions, risk) are dropped."""
    specs: list[ToolSpec] = []
    for tool in tools or []:
        name = tool.get("name")
        if not name:
            raise ConfigurationError("tool declaration is missing a name")
        schema = tool.get("inputSchema", tool.get("input_schema"))
        specs.append(
            ToolSpec(
                name=str(name),
                description=tool.get("description") or None,
                input_schema=dict(schema) if isinstance(schema, Mapping) and schema else None,
            )
        )
    return specs


@dataclass(frozen=True)
class ToolChoice:
    """Normalised ``tool_choice``: ``auto``, ``none``, ``required`` or ``tool`` + ``name``."""

    mode: str
    name: str | None = None


def parse_tool_choice(choice: str | Mapping[str, Any] | None) -> ToolChoice | None:
    if choice is None:
        return None
    if isinstance(choice, str):
        if choice in ("auto", "none", "required"):
            return ToolChoice(choice)
        raise ConfigurationError(
            f"unsupported tool_choice {choice!r} (use auto, none, required or {{'name': ...}})"
        )
    if isinstance(choice, Mapping) and choice.get("name"):
        return ToolChoice("tool", str(choice["name"]))
    raise ConfigurationError("tool_choice must be auto, none, required or {'name': <tool>}")


def active_tool_choice(tools: list[dict[str, Any]] | None, choice: Any) -> ToolChoice | None:
    """The tool choice to send — None when the request declares no tools."""
    return parse_tool_choice(choice) if tools else None


# ---- ids and arguments --------------------------------------------------------------


def synthetic_call_id(index: int, name: str) -> str:
    """Deterministic id for providers that send none: ``call_<index>_<name>``."""
    return f"call_{index}_{name}"


def dump_arguments(arguments: Mapping[str, Any]) -> str:
    """Compact JSON, insertion order — the string OpenAI-style APIs expect."""
    return json.dumps(arguments, separators=(",", ":"), ensure_ascii=False)


def parse_arguments(raw: Any, tool_name: str) -> dict[str, Any]:
    """Parse native tool-call arguments into an object.

    Accepts an already-parsed object, a JSON string, an empty string or None (no
    arguments). Anything else raises :class:`MalformedToolCallError` naming the tool only.
    """
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return {}
    value = raw
    if isinstance(raw, str):
        try:
            value = json.loads(raw)
        except ValueError:
            raise MalformedToolCallError(
                f"tool call {tool_name!r} has arguments that are not valid JSON"
            ) from None
    if not isinstance(value, dict):
        raise MalformedToolCallError(
            f"tool call {tool_name!r} has arguments that are not a JSON object"
        )
    return value


def normalise_finish_reason(
    native: str | None,
    mapping: Mapping[str, FinishReason],
    tool_calls: list[ToolCall],
) -> FinishReason | None:
    """Map a native stop reason; a plain stop that produced tool calls is ``tool_calls``."""
    reason = None if native is None else mapping.get(native, FinishReason.OTHER)
    if tool_calls and reason in (None, FinishReason.STOP):
        return FinishReason.TOOL_CALLS
    return reason


# ---- parsed provider response -------------------------------------------------------


@dataclass
class ProviderCompletion:
    """A provider response parsed into normalised parts (``from_provider_response``)."""

    content: str = ""
    tool_calls: list[ToolCall] = field(default_factory=list)
    finish_reason: FinishReason | None = None
    usage: TokenUsage = field(default_factory=TokenUsage)
    model_version: str | None = None

    def to_wire(self) -> dict[str, Any]:
        """The spec (camelCase) shape used by the shared test vectors."""
        return {
            "content": self.content,
            "toolCalls": [call.to_dict() for call in self.tool_calls],
            "finishReason": self.finish_reason.value if self.finish_reason else None,
            "usage": {
                "promptTokens": self.usage.prompt_tokens,
                "completionTokens": self.usage.completion_tokens,
                "totalTokens": self.usage.total_tokens,
            },
            "modelVersion": self.model_version,
        }


def message_text(message: Message) -> str:
    """A message's ``content`` as text; None (OpenAI-style tool-call turns) is empty."""
    return message.get("content") or ""
