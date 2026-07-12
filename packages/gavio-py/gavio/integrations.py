"""Dependency-light integration catalog helpers.

The catalog describes how Gavio fits beside common AI stack tools without
importing any of those tools. Applications can use the metadata helper to label
requests consistently across runtime events, audit records, cost reports, and
external dashboards.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class IntegrationRecipe:
    """Compatibility metadata for one ecosystem integration."""

    id: str
    name: str
    category: str
    external_owns: tuple[str, ...]
    gavio_owns: tuple[str, ...]
    gavio_surfaces: tuple[str, ...]
    recommended_exporters: tuple[str, ...]
    metadata: dict[str, str] = field(default_factory=dict)
    docs_path: str = ""
    example_path: str = ""

    def metadata_for(self, **overrides: str) -> dict[str, str]:
        """Return request metadata labels for this integration."""

        out = dict(self.metadata)
        out.update({key: str(value) for key, value in overrides.items() if value is not None})
        return out

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "name": self.name,
            "category": self.category,
            "externalOwns": list(self.external_owns),
            "gavioOwns": list(self.gavio_owns),
            "gavioSurfaces": list(self.gavio_surfaces),
            "recommendedExporters": list(self.recommended_exporters),
            "metadata": dict(self.metadata),
            "docsPath": self.docs_path,
            "examplePath": self.example_path,
        }


def list_integrations(category: str | None = None) -> list[IntegrationRecipe]:
    """List known integration recipes, optionally filtered by category."""

    recipes = list(_INTEGRATIONS)
    if category is not None:
        recipes = [recipe for recipe in recipes if recipe.category == category]
    return recipes


def get_integration(integration_id: str) -> IntegrationRecipe:
    """Return one integration recipe by id."""

    try:
        return _BY_ID[integration_id]
    except KeyError as exc:
        known = ", ".join(sorted(_BY_ID))
        raise KeyError(f"unknown Gavio integration {integration_id!r}; known: {known}") from exc


def integration_metadata(integration_id: str, **overrides: str) -> dict[str, str]:
    """Return request metadata labels for an integration."""

    return get_integration(integration_id).metadata_for(**overrides)


def compatibility_matrix() -> list[dict[str, Any]]:
    """Return the docs-friendly compatibility matrix."""

    return [
        {
            "id": recipe.id,
            "name": recipe.name,
            "category": recipe.category,
            "externalOwns": list(recipe.external_owns),
            "gavioOwns": list(recipe.gavio_owns),
            "gavioSurfaces": list(recipe.gavio_surfaces),
            "recommendedExporters": list(recipe.recommended_exporters),
            "docsPath": recipe.docs_path,
            "examplePath": recipe.example_path,
        }
        for recipe in _INTEGRATIONS
    ]


def _recipe(
    integration_id: str,
    name: str,
    category: str,
    external_owns: tuple[str, ...],
    gavio_owns: tuple[str, ...],
    gavio_surfaces: tuple[str, ...],
    recommended_exporters: tuple[str, ...],
    metadata: dict[str, str],
) -> IntegrationRecipe:
    return IntegrationRecipe(
        id=integration_id,
        name=name,
        category=category,
        external_owns=external_owns,
        gavio_owns=gavio_owns,
        gavio_surfaces=gavio_surfaces,
        recommended_exporters=recommended_exporters,
        metadata=metadata,
        docs_path=f"docs/integrations/{integration_id}.md",
        example_path=f"examples/integrations/{integration_id}/recipe.py",
    )


_INTEGRATIONS = (
    _recipe(
        "litellm",
        "LiteLLM",
        "gateway",
        (
            "multi-provider proxy",
            "virtual keys",
            "provider routing",
            "gateway rate and budget tiers",
        ),
        (
            "app-level PII and policy checks before proxy calls",
            "metadata-only audit and runtime events",
            "tenant, feature, and workflow cost labels",
        ),
        ("metadata", "runtime_events", "audit_hashes", "cost_governance", "policy_packs"),
        ("jsonl", "otel"),
        {"gateway": "litellm", "integration": "litellm", "integration_kind": "gateway"},
    ),
    _recipe(
        "portkey",
        "Portkey",
        "gateway",
        (
            "AI gateway configuration",
            "organization-level controls",
            "provider routing",
            "gateway logs",
        ),
        (
            "embedded runtime policy decisions",
            "pre/post interceptor facts",
            "metadata-only audit trail",
        ),
        ("metadata", "runtime_events", "audit_hashes", "policy_packs", "tool_runtime"),
        ("jsonl", "otel"),
        {"gateway": "portkey", "integration": "portkey", "integration_kind": "gateway"},
    ),
    _recipe(
        "helicone",
        "Helicone",
        "gateway_observability",
        ("LLM gateway analytics", "request dashboard", "prompt workflow analytics"),
        (
            "local runtime controls before and after provider calls",
            "privacy-preserving labels for correlation",
            "hash-only audit evidence",
        ),
        ("metadata", "runtime_events", "audit_hashes", "cost_governance"),
        ("jsonl",),
        {
            "gateway": "helicone",
            "integration": "helicone",
            "integration_kind": "gateway_observability",
        },
    ),
    _recipe(
        "langfuse",
        "Langfuse",
        "observability",
        ("LLM traces", "prompt management", "eval datasets", "human review workflows"),
        (
            "metadata-safe runtime facts",
            "policy, PII, cost, and tool context",
            "audit hashes without raw content",
        ),
        ("metadata", "runtime_events", "audit_hashes", "prompt_evals"),
        ("jsonl",),
        {"integration": "langfuse", "integration_kind": "observability"},
    ),
    _recipe(
        "openlit",
        "OpenLIT",
        "observability",
        ("OpenTelemetry-native observability", "fleet dashboards", "APM correlation"),
        (
            "runtime event source",
            "privacy-preserving OTel span attributes",
            "interceptor decision events",
        ),
        ("metadata", "runtime_events", "otel_spans", "cost_governance"),
        ("otel",),
        {"integration": "openlit", "integration_kind": "observability"},
    ),
    _recipe(
        "promptfoo",
        "promptfoo",
        "eval",
        ("eval suites", "red-team tests", "CI pass/fail gates"),
        (
            "production-like runtime assertions",
            "PII, policy, cost, and tool outcome signals",
            "metadata-safe eval reports",
        ),
        ("metadata", "runtime_events", "prompt_evals", "policy_packs", "tool_runtime"),
        ("jsonl",),
        {"integration": "promptfoo", "integration_kind": "eval"},
    ),
    _recipe(
        "langchain",
        "LangChain",
        "framework",
        ("chains", "agents", "tool orchestration", "memory abstractions"),
        (
            "request runtime governance around model calls",
            "callback-exportable runtime metadata",
            "tool result validation before model re-entry",
        ),
        ("metadata", "runtime_events", "tool_runtime", "audit_hashes"),
        ("jsonl", "otel"),
        {"framework": "langchain", "integration": "langchain", "integration_kind": "framework"},
    ),
    _recipe(
        "langgraph",
        "LangGraph",
        "framework",
        ("graph state", "node execution", "checkpointing", "agent orchestration"),
        (
            "per-node runtime labels",
            "policy and audit context for model/tool nodes",
            "metadata-safe replay evidence",
        ),
        ("metadata", "runtime_events", "tool_runtime", "audit_hashes"),
        ("jsonl", "otel"),
        {"framework": "langgraph", "integration": "langgraph", "integration_kind": "framework"},
    ),
    _recipe(
        "vercel-ai-sdk",
        "Vercel AI SDK",
        "framework",
        ("frontend streaming UX", "server actions", "provider convenience APIs"),
        (
            "server-side runtime governance before streaming starts",
            "metadata-only runtime export",
            "policy and cost labels for app routes",
        ),
        ("metadata", "runtime_events", "otel_spans", "policy_packs"),
        ("jsonl", "otel"),
        {
            "framework": "vercel-ai-sdk",
            "integration": "vercel-ai-sdk",
            "integration_kind": "framework",
        },
    ),
    _recipe(
        "openai-sdk",
        "OpenAI SDK",
        "provider_sdk",
        ("provider-specific API surface", "streaming primitives", "file and assistant endpoints"),
        (
            "OpenAI-compatible chat shim for governed completions",
            "runtime policy checks around provider calls",
            "metadata-safe audit and export",
        ),
        ("metadata", "runtime_events", "audit_hashes", "policy_packs"),
        ("jsonl", "otel"),
        {
            "provider_sdk": "openai",
            "integration": "openai-sdk",
            "integration_kind": "provider_sdk",
        },
    ),
)

_BY_ID = {recipe.id: recipe for recipe in _INTEGRATIONS}
