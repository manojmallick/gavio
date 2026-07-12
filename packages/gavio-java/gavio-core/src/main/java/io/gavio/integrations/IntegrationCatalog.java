package io.gavio.integrations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dependency-light catalog of common Gavio ecosystem integrations. */
public final class IntegrationCatalog {

    private static final List<IntegrationRecipe> INTEGRATIONS = List.of(
            recipe(
                    "litellm",
                    "LiteLLM",
                    "gateway",
                    List.of("multi-provider proxy", "virtual keys", "provider routing", "gateway rate and budget tiers"),
                    List.of(
                            "app-level PII and policy checks before proxy calls",
                            "metadata-only audit and runtime events",
                            "tenant, feature, and workflow cost labels"),
                    List.of("metadata", "runtime_events", "audit_hashes", "cost_governance", "policy_packs"),
                    List.of("jsonl", "otel"),
                    Map.of("gateway", "litellm", "integration", "litellm", "integration_kind", "gateway")),
            recipe(
                    "portkey",
                    "Portkey",
                    "gateway",
                    List.of(
                            "AI gateway configuration",
                            "organization-level controls",
                            "provider routing",
                            "gateway logs"),
                    List.of(
                            "embedded runtime policy decisions",
                            "pre/post interceptor facts",
                            "metadata-only audit trail"),
                    List.of("metadata", "runtime_events", "audit_hashes", "policy_packs", "tool_runtime"),
                    List.of("jsonl", "otel"),
                    Map.of("gateway", "portkey", "integration", "portkey", "integration_kind", "gateway")),
            recipe(
                    "helicone",
                    "Helicone",
                    "gateway_observability",
                    List.of("LLM gateway analytics", "request dashboard", "prompt workflow analytics"),
                    List.of(
                            "local runtime controls before and after provider calls",
                            "privacy-preserving labels for correlation",
                            "hash-only audit evidence"),
                    List.of("metadata", "runtime_events", "audit_hashes", "cost_governance"),
                    List.of("jsonl"),
                    Map.of(
                            "gateway",
                            "helicone",
                            "integration",
                            "helicone",
                            "integration_kind",
                            "gateway_observability")),
            recipe(
                    "langfuse",
                    "Langfuse",
                    "observability",
                    List.of("LLM traces", "prompt management", "eval datasets", "human review workflows"),
                    List.of(
                            "metadata-safe runtime facts",
                            "policy, PII, cost, and tool context",
                            "audit hashes without raw content"),
                    List.of("metadata", "runtime_events", "audit_hashes", "prompt_evals"),
                    List.of("jsonl"),
                    Map.of("integration", "langfuse", "integration_kind", "observability")),
            recipe(
                    "openlit",
                    "OpenLIT",
                    "observability",
                    List.of("OpenTelemetry-native observability", "fleet dashboards", "APM correlation"),
                    List.of(
                            "runtime event source",
                            "privacy-preserving OTel span attributes",
                            "interceptor decision events"),
                    List.of("metadata", "runtime_events", "otel_spans", "cost_governance"),
                    List.of("otel"),
                    Map.of("integration", "openlit", "integration_kind", "observability")),
            recipe(
                    "promptfoo",
                    "promptfoo",
                    "eval",
                    List.of("eval suites", "red-team tests", "CI pass/fail gates"),
                    List.of(
                            "production-like runtime assertions",
                            "PII, policy, cost, and tool outcome signals",
                            "metadata-safe eval reports"),
                    List.of("metadata", "runtime_events", "prompt_evals", "policy_packs", "tool_runtime"),
                    List.of("jsonl"),
                    Map.of("integration", "promptfoo", "integration_kind", "eval")),
            recipe(
                    "langchain",
                    "LangChain",
                    "framework",
                    List.of("chains", "agents", "tool orchestration", "memory abstractions"),
                    List.of(
                            "request runtime governance around model calls",
                            "callback-exportable runtime metadata",
                            "tool result validation before model re-entry"),
                    List.of("metadata", "runtime_events", "tool_runtime", "audit_hashes"),
                    List.of("jsonl", "otel"),
                    Map.of("framework", "langchain", "integration", "langchain", "integration_kind", "framework")),
            recipe(
                    "langgraph",
                    "LangGraph",
                    "framework",
                    List.of("graph state", "node execution", "checkpointing", "agent orchestration"),
                    List.of(
                            "per-node runtime labels",
                            "policy and audit context for model/tool nodes",
                            "metadata-safe replay evidence"),
                    List.of("metadata", "runtime_events", "tool_runtime", "audit_hashes"),
                    List.of("jsonl", "otel"),
                    Map.of("framework", "langgraph", "integration", "langgraph", "integration_kind", "framework")),
            recipe(
                    "vercel-ai-sdk",
                    "Vercel AI SDK",
                    "framework",
                    List.of("frontend streaming UX", "server actions", "provider convenience APIs"),
                    List.of(
                            "server-side runtime governance before streaming starts",
                            "metadata-only runtime export",
                            "policy and cost labels for app routes"),
                    List.of("metadata", "runtime_events", "otel_spans", "policy_packs"),
                    List.of("jsonl", "otel"),
                    Map.of(
                            "framework",
                            "vercel-ai-sdk",
                            "integration",
                            "vercel-ai-sdk",
                            "integration_kind",
                            "framework")),
            recipe(
                    "openai-sdk",
                    "OpenAI SDK",
                    "provider_sdk",
                    List.of("provider-specific API surface", "streaming primitives", "file and assistant endpoints"),
                    List.of(
                            "OpenAI-compatible chat shim for governed completions",
                            "runtime policy checks around provider calls",
                            "metadata-safe audit and export"),
                    List.of("metadata", "runtime_events", "audit_hashes", "policy_packs"),
                    List.of("jsonl", "otel"),
                    Map.of(
                            "provider_sdk",
                            "openai",
                            "integration",
                            "openai-sdk",
                            "integration_kind",
                            "provider_sdk")));

    private IntegrationCatalog() {}

    public static List<IntegrationRecipe> list() {
        return List.copyOf(INTEGRATIONS);
    }

    public static List<IntegrationRecipe> listByCategory(String category) {
        return INTEGRATIONS.stream().filter(recipe -> recipe.category().equals(category)).toList();
    }

    public static IntegrationRecipe get(String id) {
        Optional<IntegrationRecipe> recipe = INTEGRATIONS.stream()
                .filter(item -> item.id().equals(id))
                .findFirst();
        if (recipe.isPresent()) {
            return recipe.get();
        }
        throw new IllegalArgumentException("unknown Gavio integration \"" + id + "\"; known: " + knownIds());
    }

    public static Map<String, String> metadata(String id) {
        return metadata(id, Map.of());
    }

    public static Map<String, String> metadata(String id, Map<String, String> overrides) {
        return get(id).metadataFor(overrides);
    }

    public static List<Map<String, Object>> compatibilityMatrix() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IntegrationRecipe recipe : INTEGRATIONS) {
            Map<String, Object> row = new LinkedHashMap<>(recipe.toMap());
            row.remove("metadata");
            rows.add(row);
        }
        return rows;
    }

    private static IntegrationRecipe recipe(
            String id,
            String name,
            String category,
            List<String> externalOwns,
            List<String> gavioOwns,
            List<String> gavioSurfaces,
            List<String> recommendedExporters,
            Map<String, String> metadata) {
        return new IntegrationRecipe(
                id,
                name,
                category,
                externalOwns,
                gavioOwns,
                gavioSurfaces,
                recommendedExporters,
                metadata,
                "docs/integrations/" + id + ".md",
                "examples/integrations/" + id + "/recipe.py");
    }

    private static String knownIds() {
        return String.join(", ", INTEGRATIONS.stream().map(IntegrationRecipe::id).sorted().toList());
    }
}
