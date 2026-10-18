"""Shared enums and type aliases for the Gavio gateway."""

from __future__ import annotations

import copy
import hashlib
import json
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

# A provider-agnostic chat message. Kept as a plain dict for ergonomics and
# zero-dependency JSON serialisation. Values are strings except for the tool
# calling keys (F-ADP-03): an assistant message may carry ``tool_calls`` (a list
# of :meth:`ToolCall.to_dict` dicts) and a tool message ``tool_call_id`` /
# ``is_error``.
Message = dict[str, Any]


class Provider(str, Enum):
    """Supported LLM providers. String-valued for easy config + logging."""

    OPENAI = "openai"
    ANTHROPIC = "anthropic"
    GEMINI = "gemini"
    AZURE_OPENAI = "azure_openai"
    OPENROUTER = "openrouter"
    OLLAMA = "ollama"
    BEDROCK = "bedrock"
    COHERE = "cohere"
    MOCK = "mock"

    @classmethod
    def coerce(cls, value: Provider | str) -> Provider:
        """Accept either an enum member or its string value."""
        if isinstance(value, Provider):
            return value
        return cls(value.lower())


class CacheType(str, Enum):
    EXACT = "exact"
    SEMANTIC = "semantic"


class PiiMode(str, Enum):
    """What PiiGuard does with a detected entity."""

    REDACT = "redact"  # replace with a typed placeholder token
    MASK = "mask"  # replace characters with asterisks
    TAG = "tag"  # annotate inline but keep the value
    BLOCK = "block"  # raise and refuse the request


class Sensitivity(str, Enum):
    STRICT = "strict"
    BALANCED = "balanced"
    PERMISSIVE = "permissive"


class GuardrailOutcome(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    HITL = "HITL"


class FinishReason(str, Enum):
    """Provider-agnostic stop reason (F-ADP-03). Mapped from each provider's native value."""

    STOP = "stop"
    LENGTH = "length"
    TOOL_CALLS = "tool_calls"
    CONTENT_FILTER = "content_filter"
    OTHER = "other"


def canonical_json(value: Any) -> str:
    """Canonical JSON used for hashing: sorted keys, no whitespace, UTF-8 not escaped."""
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


@dataclass(frozen=True)
class ToolCall:
    """A model-requested tool invocation, normalised across providers (F-ADP-03).

    ``arguments`` is always a parsed JSON object. ``id`` is the provider's call id,
    or a deterministic synthetic id for providers that send none (Gemini, Ollama).
    """

    id: str
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)

    def arguments_hash(self) -> str:
        """SHA-256 hex of the canonical JSON of ``arguments`` — what audit records keep."""
        return hashlib.sha256(canonical_json(self.arguments).encode("utf-8")).hexdigest()

    def result(self, content: str, *, is_error: bool = False) -> ToolResult:
        """Build the :class:`ToolResult` answering this call."""
        return ToolResult(
            tool_call_id=self.id, content=content, name=self.name, is_error=is_error
        )

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "name": self.name, "arguments": copy.deepcopy(self.arguments)}

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> ToolCall:
        if not isinstance(data, dict):
            raise TypeError(f"tool call must be a dict, got {type(data).__name__}")
        arguments = data.get("arguments")
        if arguments is None:
            arguments = {}
        if not isinstance(arguments, dict):
            raise TypeError("tool call arguments must be a JSON object")
        return cls(id=str(data["id"]), name=str(data["name"]), arguments=arguments)


@dataclass(frozen=True)
class ToolResult:
    """The outcome of executing a :class:`ToolCall` (F-ADP-03)."""

    tool_call_id: str
    content: str
    name: str | None = None
    is_error: bool = False

    def to_message(self) -> Message:
        """The ``role: "tool"`` message to append to the conversation."""
        message: Message = {
            "role": "tool",
            "tool_call_id": self.tool_call_id,
            "content": self.content,
        }
        if self.name:
            message["name"] = self.name
        if self.is_error:
            message["is_error"] = True
        return message


@dataclass(frozen=True)
class TokenUsage:
    """Token accounting for a single completion."""

    prompt_tokens: int = 0
    completion_tokens: int = 0

    @property
    def total_tokens(self) -> int:
        return self.prompt_tokens + self.completion_tokens

    def to_dict(self) -> dict[str, int]:
        return {
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "total_tokens": self.total_tokens,
        }


@dataclass(frozen=True)
class RagChunk:
    """A single retrieved source that contributed to a prompt.

    Carries a *reference* to the source — never the retrieved text — so prompt
    lineage stays within the audit record's metadata-only contract.
    """

    source: str
    chunk_id: str | None = None
    score: float | None = None

    def to_dict(self) -> dict[str, Any]:
        return {"source": self.source, "chunk_id": self.chunk_id, "score": self.score}


@dataclass
class PromptLineage:
    """Provenance for a rendered prompt (F-OBS-04).

    Records the template, the variable bindings interpolated into it, and the
    RAG chunk *sources* retrieved for it. Attached to a :class:`GavioRequest` by
    the caller and copied into the :class:`AuditRecord` so any prompt can be
    reconstructed and debugged. RAG chunk text is never stored — only source
    references (see :class:`RagChunk`).
    """

    template_id: str | None = None
    template_version: str | None = None
    variables: dict[str, Any] = field(default_factory=dict)
    rag_chunks: list[RagChunk] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "template_id": self.template_id,
            "template_version": self.template_version,
            "variables": dict(self.variables),
            "rag_chunks": [c.to_dict() for c in self.rag_chunks],
        }
