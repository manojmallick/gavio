"""Gavio — AI request runtime and inspector for production systems.

Stable public API surface (v2.0.0):

    from gavio import Gateway, GavioRequest, GavioResponse, Provider

See https://gavio.io for documentation. MIT licensed.
"""

from __future__ import annotations

from .context import InterceptorContext
from .control_plane import ControlPlaneClient, ControlPlaneError, load_control_plane_config
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
    ToolRuntimeError,
)
from .exporters import GavioRuntimeExporter, JsonlRuntimeExporter, OtelSpanExporter
from .gateway import Gateway, GatewayBuilder
from .integrations import (
    IntegrationRecipe,
    compatibility_matrix,
    get_integration,
    integration_metadata,
    list_integrations,
)
from .platform_runtime import (
    PlatformRuntimeVerification,
    build_platform_runtime_profile,
    platform_profile_hash,
    platform_runtime_readiness,
    verify_platform_runtime_profile,
)
from .prompts import EvalSuite, PromptRegistry, PromptTemplate, RenderedPrompt
from .request import GavioRequest
from .response import GavioResponse
from .trust import (
    TrustBundleVerification,
    build_production_trust_bundle,
    trust_bundle_hash,
    verify_production_trust_bundle,
)
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

__version__ = "2.0.0"

__all__ = [
    "__version__",
    "Gateway",
    "GatewayBuilder",
    "IntegrationRecipe",
    "list_integrations",
    "get_integration",
    "integration_metadata",
    "compatibility_matrix",
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
    "GavioRuntimeExporter",
    "JsonlRuntimeExporter",
    "OtelSpanExporter",
    "ControlPlaneClient",
    "ControlPlaneError",
    "load_control_plane_config",
    "PromptTemplate",
    "RenderedPrompt",
    "PromptRegistry",
    "EvalSuite",
    "PlatformRuntimeVerification",
    "build_platform_runtime_profile",
    "platform_runtime_readiness",
    "platform_profile_hash",
    "verify_platform_runtime_profile",
    "TrustBundleVerification",
    "build_production_trust_bundle",
    "trust_bundle_hash",
    "verify_production_trust_bundle",
    # exceptions
    "GavioError",
    "ConfigurationError",
    "ProviderError",
    "ProviderUnavailableError",
    "RateLimitError",
    "ServerError",
    "ToolRuntimeError",
    "PiiBlockedError",
    "BudgetExceededError",
    "GuardrailViolationError",
]
