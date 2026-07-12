"""Dependency-light integration catalog helpers.

The catalog describes how Gavio fits beside common AI stack tools without
importing any of those tools. Applications can use the metadata helper to label
requests consistently across runtime events, audit records, cost reports, and
external dashboards.
"""

from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from dataclasses import dataclass, field
from typing import Any

ADAPTER_SCHEMA_VERSION = "gavio.integration-adapter.v1"
_CONTENT_KEYS = frozenset(
    {
        "messages",
        "content",
        "diff",
        "prompt",
        "response",
        "output",
        "renderedPrompt",
        "rendered_prompt",
    }
)


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


def integration_adapter_payload(
    integration_id: str,
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return a dependency-light adapter payload for one ecosystem tool.

    The returned payload is intentionally metadata-only. Content-bearing keys
    in ``metadata`` are replaced with stable SHA-256 hashes, while runtime-event
    source content is ignored in favor of trace/status/cost/model summaries.
    """

    recipe = get_integration(integration_id)
    source_map = _source_to_dict(source)
    labels = _adapter_metadata(integration_id, source_map, metadata or {})
    summary = _adapter_summary(source_map)
    op = operation or _default_operation(recipe.id)
    adapter_payload = _payload_for(recipe.id, labels, summary, op)
    return {
        "schemaVersion": ADAPTER_SCHEMA_VERSION,
        "adapter": recipe.id,
        "target": recipe.id,
        "kind": recipe.category,
        "payload": adapter_payload,
    }


def litellm_adapter_payload(
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return LiteLLM completion kwargs with Gavio metadata and headers."""

    return integration_adapter_payload(
        "litellm",
        source,
        metadata=metadata,
        operation=operation,
    )


def promptfoo_adapter_payload(
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return a promptfoo defaultTest/vars payload for Gavio runtime assertions."""

    return integration_adapter_payload(
        "promptfoo",
        source,
        metadata=metadata,
        operation=operation,
    )


def langfuse_adapter_payload(
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return Langfuse trace/generation metadata payloads."""

    return integration_adapter_payload(
        "langfuse",
        source,
        metadata=metadata,
        operation=operation,
    )


def openlit_adapter_payload(
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return OpenLIT/OTel attribute payloads."""

    return integration_adapter_payload(
        "openlit",
        source,
        metadata=metadata,
        operation=operation,
    )


def langchain_adapter_payload(
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return LangChain RunnableConfig metadata and tags."""

    return integration_adapter_payload(
        "langchain",
        source,
        metadata=metadata,
        operation=operation,
    )


def langgraph_adapter_payload(
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return LangGraph RunnableConfig metadata, tags, and configurable ids."""

    return integration_adapter_payload(
        "langgraph",
        source,
        metadata=metadata,
        operation=operation,
    )


def vercel_ai_sdk_adapter_payload(
    source: Any | None = None,
    *,
    metadata: dict[str, Any] | None = None,
    operation: str | None = None,
) -> dict[str, Any]:
    """Return Vercel AI SDK request headers and telemetry metadata."""

    return integration_adapter_payload(
        "vercel-ai-sdk",
        source,
        metadata=metadata,
        operation=operation,
    )


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


def _payload_for(
    integration_id: str,
    labels: dict[str, Any],
    summary: dict[str, Any],
    operation: str,
) -> dict[str, Any]:
    tags = _tags(labels, integration_id)
    trace_id = str(summary.get("traceId") or labels.get("trace_id") or "")
    merged = _clean_dict({**labels, **_prefix_summary(summary)})
    if integration_id == "litellm":
        return {
            "completionKwargs": {
                "metadata": merged,
                "extraHeaders": _trace_headers(trace_id, integration_id),
            }
        }
    if integration_id == "promptfoo":
        return {
            "defaultTest": {
                "metadata": labels,
                "assert": [
                    {
                        "type": "javascript",
                        "value": "context.vars.gavio.status !== 'error'",
                        "metric": "gavio_status",
                    },
                    {
                        "type": "javascript",
                        "value": "(context.vars.gavio.failedCases ?? 0) === 0",
                        "metric": "gavio_eval_failures",
                    },
                ],
            },
            "vars": {"gavio": summary},
        }
    if integration_id == "langfuse":
        return {
            "trace": {
                "id": trace_id,
                "name": operation,
                "metadata": merged,
                "tags": tags,
            },
            "generation": {
                "id": f"{trace_id}:generation" if trace_id else "gavio:generation",
                "traceId": trace_id,
                "name": "gavio.request",
                "model": summary.get("model"),
                "metadata": summary,
            },
        }
    if integration_id == "openlit":
        attributes = _clean_dict(
            {
                "gavio.integration": integration_id,
                "gavio.trace_id": trace_id,
                "gavio.event_type": summary.get("eventType"),
                "gavio.status": summary.get("status"),
                "gavio.latency_ms": summary.get("latencyMs"),
                "gen_ai.system": summary.get("provider"),
                "gen_ai.request.model": summary.get("model"),
                "gen_ai.usage.cost": summary.get("costUsd"),
            }
        )
        return {
            "span": {
                "name": operation,
                "attributes": {**attributes, **_prefix_labels(labels)},
            }
        }
    if integration_id == "langchain":
        return {
            "runnableConfig": {
                "run_name": operation,
                "metadata": merged,
                "tags": tags,
            }
        }
    if integration_id == "langgraph":
        workflow = str(labels.get("workflow") or trace_id or "gavio")
        return {
            "runnableConfig": {
                "run_name": operation,
                "metadata": merged,
                "tags": tags,
                "configurable": {
                    "thread_id": workflow,
                    "gavio_trace_id": trace_id,
                },
            }
        }
    if integration_id == "vercel-ai-sdk":
        return {
            "request": {
                "headers": _trace_headers(trace_id, integration_id),
                "experimental_telemetry": {
                    "isEnabled": True,
                    "functionId": operation,
                    "metadata": merged,
                },
            }
        }
    return {"metadata": merged, "summary": summary}


def _source_to_dict(source: Any | None) -> dict[str, Any]:
    if source is None:
        return {}
    if isinstance(source, dict):
        return deepcopy(source)
    to_dict = getattr(source, "to_dict", None)
    if callable(to_dict):
        value = to_dict()
        if isinstance(value, dict):
            return deepcopy(value)
    raise TypeError("integration adapter source must be a mapping or expose to_dict()")


def _adapter_metadata(
    integration_id: str,
    source: dict[str, Any],
    metadata: dict[str, Any],
) -> dict[str, Any]:
    labels = integration_metadata(integration_id)
    labels.update(_sanitize_metadata(metadata))
    trace_id = _trace_id(source)
    if trace_id and "trace_id" not in labels:
        labels["trace_id"] = trace_id
    return _clean_dict(labels)


def _adapter_summary(source: dict[str, Any]) -> dict[str, Any]:
    data = source.get("data") if isinstance(source.get("data"), dict) else {}
    summary: dict[str, Any] = {}
    _copy_first(summary, "traceId", source, data, "traceId", "trace_id")
    if "type" in source:
        summary["eventType"] = source["type"]
    for key in (
        "status",
        "latencyMs",
        "costUsd",
        "piiEntityTypes",
        "interceptorsFired",
        "model",
        "provider",
        "score",
        "suiteId",
        "totalCases",
        "passedCases",
        "failedCases",
        "passed",
        "bundleId",
    ):
        _copy_first(summary, key, source, data, key)
    return _clean_dict(summary)


def _copy_first(
    out: dict[str, Any],
    out_key: str,
    primary: dict[str, Any],
    secondary: dict[str, Any],
    *keys: str,
) -> None:
    for key in keys:
        if key in primary and primary[key] is not None:
            out[out_key] = deepcopy(primary[key])
            return
        if key in secondary and secondary[key] is not None:
            out[out_key] = deepcopy(secondary[key])
            return


def _trace_id(source: dict[str, Any]) -> str | None:
    data = source.get("data") if isinstance(source.get("data"), dict) else {}
    value = source.get("traceId") or source.get("trace_id") or data.get("traceId")
    return str(value) if value not in (None, "") else None


def _sanitize_metadata(value: Any) -> Any:
    if isinstance(value, dict):
        out: dict[str, Any] = {}
        for key, raw in value.items():
            if raw is None:
                continue
            text_key = str(key)
            if text_key in _CONTENT_KEYS:
                out[f"{_camel_hash_key(text_key)}"] = _hash_value(raw)
            else:
                out[text_key] = _sanitize_metadata(raw)
        return out
    if isinstance(value, (list, tuple)):
        return [_sanitize_metadata(item) for item in value if item is not None]
    return value


def _camel_hash_key(key: str) -> str:
    if "_" in key:
        parts = key.split("_")
        key = parts[0] + "".join(part.capitalize() for part in parts[1:])
    return f"{key}Hash"


def _hash_value(value: Any) -> str:
    if isinstance(value, str):
        payload = value
    else:
        payload = json.dumps(value, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def _clean_dict(value: dict[str, Any]) -> dict[str, Any]:
    return {key: item for key, item in value.items() if item is not None}


def _tags(labels: dict[str, Any], integration_id: str) -> list[str]:
    tags = ["gavio", f"integration:{integration_id}"]
    for key in ("tenant", "feature", "environment", "workflow"):
        value = labels.get(key)
        if value not in (None, ""):
            tags.append(f"{key}:{value}")
    return tags


def _trace_headers(trace_id: str, integration_id: str) -> dict[str, str]:
    headers = {"x-gavio-integration": integration_id}
    if trace_id:
        headers["x-gavio-trace-id"] = trace_id
    return headers


def _prefix_summary(summary: dict[str, Any]) -> dict[str, Any]:
    return {f"gavio.{key}": value for key, value in summary.items() if value is not None}


def _prefix_labels(labels: dict[str, Any]) -> dict[str, Any]:
    return {f"gavio.label.{key}": value for key, value in labels.items() if value is not None}


def _default_operation(integration_id: str) -> str:
    if integration_id == "promptfoo":
        return "gavio.eval"
    if integration_id == "vercel-ai-sdk":
        return "gavio.route"
    return "gavio.request"


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
