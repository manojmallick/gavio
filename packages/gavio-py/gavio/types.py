"""Shared enums and type aliases for the Gavio gateway."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any

# A provider-agnostic chat message. Kept as a plain dict for ergonomics and
# zero-dependency JSON serialisation.
Message = dict[str, str]


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
