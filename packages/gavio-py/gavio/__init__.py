"""Gavio — AI request runtime and inspector for production systems.

Stable public API surface (v2.7.0):

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
    ADAPTER_SCHEMA_VERSION,
    IntegrationRecipe,
    compatibility_matrix,
    get_integration,
    integration_adapter_payload,
    integration_metadata,
    langchain_adapter_payload,
    langfuse_adapter_payload,
    langgraph_adapter_payload,
    list_integrations,
    litellm_adapter_payload,
    openlit_adapter_payload,
    promptfoo_adapter_payload,
    vercel_ai_sdk_adapter_payload,
)
from .platform_runtime import (
    PlatformRuntimeVerification,
    build_platform_runtime_profile,
    platform_profile_hash,
    platform_runtime_readiness,
    verify_platform_runtime_profile,
)
from .prompts import (
    EvalFailureTriage,
    EvalSuite,
    PromptEvalLink,
    PromptRegistry,
    PromptReleaseBundle,
    PromptTemplate,
    PromptVersionGate,
    PromptWorkflowResult,
    RenderedPrompt,
    build_prompt_release_bundle,
    evaluate_prompt_version_gate,
    evaluate_prompt_workflow,
)
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
from .workflow import (
    PlatformWorkflowReleaseResult,
    build_platform_workflow_release,
    platform_workflow_release_hash,
    run_platform_workflow_release_file,
)

__version__ = "2.7.0"

__all__ = [
    "__version__",
    "Gateway",
    "GatewayBuilder",
    "ADAPTER_SCHEMA_VERSION",
    "IntegrationRecipe",
    "list_integrations",
    "get_integration",
    "integration_metadata",
    "compatibility_matrix",
    "integration_adapter_payload",
    "litellm_adapter_payload",
    "promptfoo_adapter_payload",
    "langfuse_adapter_payload",
    "openlit_adapter_payload",
    "langchain_adapter_payload",
    "langgraph_adapter_payload",
    "vercel_ai_sdk_adapter_payload",
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
    "EvalFailureTriage",
    "PromptEvalLink",
    "PromptVersionGate",
    "PromptWorkflowResult",
    "PromptReleaseBundle",
    "evaluate_prompt_version_gate",
    "evaluate_prompt_workflow",
    "build_prompt_release_bundle",
    "PlatformRuntimeVerification",
    "build_platform_runtime_profile",
    "platform_runtime_readiness",
    "platform_profile_hash",
    "verify_platform_runtime_profile",
    "TrustBundleVerification",
    "build_production_trust_bundle",
    "trust_bundle_hash",
    "verify_production_trust_bundle",
    "PlatformWorkflowReleaseResult",
    "build_platform_workflow_release",
    "platform_workflow_release_hash",
    "run_platform_workflow_release_file",
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
