"""Shared enums and type aliases for the Gavio gateway."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

# A provider-agnostic chat message. Kept as a plain dict for ergonomics and
# zero-dependency JSON serialisation.
Message = dict[str, str]


class Provider(str, Enum):
    """Supported LLM providers. String-valued for easy config + logging."""

    OPENAI = "openai"
    ANTHROPIC = "anthropic"
    GEMINI = "gemini"
    AZURE_OPENAI = "azure_openai"
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
