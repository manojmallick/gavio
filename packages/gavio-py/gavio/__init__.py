"""Gavio — the open standard AI gateway for production systems.

Public API surface (v0.1.0):

    from gavio import Gateway, GavioRequest, GavioResponse, Provider

See https://gavio.io for documentation. MIT licensed.
"""

from __future__ import annotations

from .context import InterceptorContext
from .exceptions import (
    BudgetExceededError,
    ConfigurationError,
    GavioError,
    GuardrailViolationError,
    PiiBlockedError,
    ProviderError,
    ProviderUnavailableError,
    RateLimitError,
    ServerError,
)
from .gateway import Gateway, GatewayBuilder
from .request import GavioRequest
from .response import GavioResponse
from .types import (
    CacheType,
    GuardrailOutcome,
    Message,
    PiiMode,
    PromptLineage,
    Provider,
    RagChunk,
    Sensitivity,
    TokenUsage,
)

__version__ = "0.2.0"

__all__ = [
    "__version__",
    "Gateway",
    "GatewayBuilder",
    "GavioRequest",
    "GavioResponse",
    "InterceptorContext",
    "Provider",
    "Message",
    "TokenUsage",
    "PromptLineage",
    "RagChunk",
    "CacheType",
    "PiiMode",
    "Sensitivity",
    "GuardrailOutcome",
    # exceptions
    "GavioError",
    "ConfigurationError",
    "ProviderError",
    "ProviderUnavailableError",
    "RateLimitError",
    "ServerError",
    "PiiBlockedError",
    "BudgetExceededError",
    "GuardrailViolationError",
]
