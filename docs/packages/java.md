# Java SDK (`io.github.manojmallick:gavio-*`)

> Maven Central · Java 17+ · immutable records + builders · `CompletableFuture`
> async · zero mandatory dependencies

Source: [`packages/gavio-java`](../../packages/gavio-java/). Multi-artifact so you
pull only what you need.

- [Install](#install)
- [Artifacts](#artifacts)
- [Gateway API](#gateway-api)
- [Interceptors](#interceptors)
- [Providers](#providers)
- [Runtime export](#runtime-export)
- [Ecosystem Integrations](#ecosystem-integrations)
- [Platform Runtime Profile](#platform-runtime-profile)
- [Production Trust Package](#production-trust-package)
- [Prompt Registry + Evals](#prompt-registry--evals)
- [Testing](#testing)
- [Notes](#notes)

---

## Install

**Maven**
```xml
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-core</artifactId>
  <version>2.4.0</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-pii</artifactId>
  <version>2.4.0</version>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
implementation("io.github.manojmallick:gavio-core:2.4.0")
implementation("io.github.manojmallick:gavio-interceptor-pii:2.4.0")
```

> The Maven **groupId** is `io.github.manojmallick`; the Java **package** in
> source is `io.gavio.*` (they don't need to match).

---

## Artifacts

| Artifact | Contains |
|---|---|
| `gavio-core` | Gateway, request/response records, interceptor chain, Tool Runtime, Platform Runtime Profile, Production Trust Package, providers base, Mock |
| `gavio-interceptor-pii` | `PiiGuard`, scanners (Email/Iban/Bsn/CreditCard/Phone/IpAddress/Ssn/Secret) |
| `gavio-interceptor-audit` | `AuditInterceptor`, `AuditRecord`, `StdoutSink`, hash-chain + call-graph |
| `gavio-interceptor-cache` | `SemanticCache`, `MemoryCacheBackend`, `RedisCacheBackend`/`RedisVectorBackend` (F-CACHE-04) |
| `gavio-interceptor-reliability` | `RetryInterceptor`, `TimeoutPolicy`, `FallbackChain`, `CircuitBreaker`, `LoadBalancer` |
| `gavio-interceptor-governance` | `CostControl`, `RateLimiter`, `ModelPolicy`, `CostRouter` (F-GOV-06) |
| `gavio-interceptor-guardrails` | `GuardrailsInterceptor`, JSON-schema + regex validators |
| `gavio-interceptor-metrics` | `MetricsInterceptor`, `PrometheusMetrics` (F-OBS-08) |
| `gavio-interceptor-quality` | `RiskScorer`, `RiskWeights` (F-QUA-06) |
| `gavio-provider-openai` | `OpenAiAdapter` (+ `GavioOpenAI` drop-in shim) |
| `gavio-provider-anthropic` | `AnthropicAdapter` |
| `gavio-provider-gemini` | `GeminiAdapter` |
| `gavio-provider-azure` | `AzureOpenAiAdapter` |
| `gavio-provider-openrouter` | `OpenRouterAdapter` |
| `gavio-provider-ollama` | `OllamaAdapter` (local, free) |
| `gavio-testing` | `GavioTestKit`, `MockProvider`, `GavioAssertions` |

---

## Gateway API

Fluent builder; `complete` returns `CompletableFuture<GavioResponse>`.

```java
import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.types.Provider;
import io.gavio.interceptors.pii.PiiGuard;

Gateway gw = Gateway.builder()
    .provider(Provider.ANTHROPIC)          // reads ANTHROPIC_API_KEY
    .model("claude-sonnet-4-6")
    .use(new PiiGuard())
    .build();

GavioResponse r = gw.complete(GavioRequest.builder()
        .message("user", "Hello")
        .agentId("my-agent")
        .sessionId("sess-123")
        .option("temperature", 0.7)
        .option("maxTokens", 1000)
        .build()).join();

r.content();            // String, PII restored
r.costUsd();            // double
r.traceId();            // UUID v7
r.usage().totalTokens();
r.interceptorsFired();  // List<String>
r.audit();              // AuditRecord
```

**Builder options:** `.provider()`, `.model()`, `.adapter(custom)`, `.use(...)`,
`.devMode(true)`, `.dryRun(true)`, `.exporter(new JsonlRuntimeExporter(...))`,
`.exporter(new OtelSpanExporter(...))`.

---

## Interceptors

Most have a builder; `PiiGuard` also has a no-arg constructor (default scanners).

```java
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.types.Sensitivity;
import io.gavio.types.PiiMode;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.audit.sinks.StdoutSink;
import io.gavio.interceptors.reliability.RetryInterceptor;
import io.gavio.interceptors.reliability.TimeoutPolicy;
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor;

Gateway gw = Gateway.builder()
    .provider(Provider.ANTHROPIC).model("claude-sonnet-4-6")
    .use(AuditInterceptor.builder().sink(new StdoutSink(true)).build())   // outermost
    .use(PiiGuard.builder().sensitivity(Sensitivity.STRICT).mode(PiiMode.REDACT).build())
    .use(TimeoutPolicy.builder().timeoutSeconds(30).build())
    .use(RetryInterceptor.builder().maxAttempts(3).baseDelayMs(500).jitter(true).build())
    .build();
```

Retry / timeout / fallback implement `ExecutorPolicy` and wrap the provider call
(first-registered = outermost). See [interceptors.md](../interceptors.md).

Cost Intelligence (v0.11.0) reads scalar labels from request metadata:

```java
GavioRequest request = GavioRequest.builder()
    .message("user", "price this")
    .metadata("costDimensions", Map.of(
        "tenant", "acme",
        "feature", "claims",
        "endpoint", "/chat"))
    .build();
```

Those labels can be used with `/api/stats?group_by=tenant` and
`/api/cost-report?group_by=feature`.

Cost Governance v2 (v1.2.0) adds policy/decision contracts and budget-aware
reports:

```java
var policy = new BudgetPolicyV2(
    "tenant-monthly", "tenant", "acme", "monthly", 500.0, 0.8,
    "fallback", List.of(), "gpt-4o-mini", Map.of());

Gateway gw = Gateway.builder()
    .use(BudgetPolicyControl.builder(policy)
        .estimatedRequestCostUsd(0.02)
        .build())
    .build();
```

### Tool Runtime

`ToolRuntimeInterceptor` validates tool metadata from request `metadata("tools",
...)` before tool outputs re-enter model context. It supports declared
input/output schemas, freshness/TTL checks, conflict detection across configured
result keys, confidence scoring, and provenance records under
`ctx.tools().get("runtime")`.
Tool Runtime v2 adds registry-backed permissions, approval gates, replay
records, and MCP metadata capture through the same `metadata("tools", ...)`
object.

```java
Gateway gw = Gateway.builder()
    .devMode(true)
    .use(ToolRuntimeInterceptor.builder()
        .onFailure(ToolRuntimeInterceptor.OnFailure.ERROR)
        .build())
    .build();

gw.complete(GavioRequest.builder()
    .message("user", "summarize inventory")
    .metadata("tools", Map.of("calls", List.of(Map.of(
        "id", "inventory-1",
        "name", "inventory",
        "source", "warehouse",
        "created_at", "2026-07-12T12:00:00Z",
        "ttl_seconds", 60,
        "result", Map.of("sku", "SKU-1", "quantity", 4),
        "output_schema", Map.of("required", List.of("sku", "quantity"))))))
    .build()).join();
```

### Policy packs (v0.12.0)

Policy packs expose scanner composition plus manifest metadata. Existing
scanner factories still work, but the built-in core and FinTech packs are now
first-class:

```java
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.pii.policy.PolicyAction;
import io.gavio.interceptors.pii.policy.PolicyPacks;
import io.gavio.interceptors.pii.policy.RedactionStrategy;
import io.gavio.interceptors.pii.policy.RegexPolicyRule;
import java.util.List;

var fintech = PolicyPacks.fintech();
System.out.println(fintech.manifest().get("detectors"));

var custom = PolicyPacks.custom(
    "acme.internal",
    "Acme Internal IDs",
    "1.0.0",
    "custom",
    List.of(new RegexPolicyRule("employee_id", "EMPLOYEE_ID", "\\bEMP-[0-9]{6}\\b")),
    PolicyAction.FLAG,
    RedactionStrategy.HASH,
    List.of("INTERNAL_IDENTIFIER"),
    "Custom organization policy pack.");

var guard = PiiGuard.builder()
    .scanners(PolicyPacks.scanners(PolicyPacks.core(), fintech, custom))
    .build();
```

---

## Providers

| Provider | `Provider.` | Env var |
|---|---|---|
| Anthropic | `ANTHROPIC` | `ANTHROPIC_API_KEY` |
| OpenAI | `OPENAI` | `OPENAI_API_KEY` |
| Gemini | `GEMINI` | `GEMINI_API_KEY` |
| Azure OpenAI | `AZURE_OPENAI` | `AZURE_OPENAI_API_KEY` + `AZURE_OPENAI_ENDPOINT` |
| OpenRouter | `OPENROUTER` | `OPENROUTER_API_KEY` |
| Ollama | `OLLAMA` | — (local; `OLLAMA_HOST`) |
| Mock | dev mode / `MockProvider` | — |

Gemini, Azure OpenAI, and Ollama were added in **v0.2.0**; OpenRouter was added
in **v0.13.0** (add the matching `gavio-provider-*` artifact).

OpenRouter accepts direct adapter options for custom base URLs and optional
attribution headers:

```java
Gateway gw = Gateway.builder()
    .adapter(OpenRouterAdapter.builder()
        .apiKey(System.getenv("OPENROUTER_API_KEY"))
        .httpReferer("https://app.example")
        .appTitle("Gavio")
        .build())
    .model("openai/gpt-4o")
    .build();
```

Adapters use `java.net.http.HttpClient` (async `sendAsync`) with a hand-rolled
JSON reader/writer — no external JSON dependency.

---

## Inspector

Enable the embedded pipeline visualizer (`F-DX-09/10`, off by default) and open
`http://127.0.0.1:7411` — live traces, waterfalls, PII diffs, agent call
graphs, replay, stats. Full guide: [docs/inspector.md](../inspector.md).

```java
Gateway gw = Gateway.builder().devMode(true).inspect(true).build();
```

The embedded server exposes the same JSON API in every SDK (`/api/traces`,
`/api/dag`, `/api/stats`, …); the store-backed `gavio inspect --store` CLI is
Python-only.

## Runtime export

Runtime export (v1.1.0, `F-EXP-01`) writes the Inspector event envelope as
metadata-safe JSONL. Adding an exporter enables metadata-mode events without
starting the Inspector HTTP server.

```java
import io.gavio.exporters.JsonlRuntimeExporter;

Gateway gw = Gateway.builder()
    .devMode(true)
    .exporter(new JsonlRuntimeExporter(Path.of("runtime-events.jsonl")))
    .build();
```

The JSONL exporter strips `messages`, `content`, and `diff` by default, even if
the local Inspector runs in `FULL` mode. See [runtime events](../runtime-events.md)
and [integrations](../integrations.md).

Observability + OTel (v1.3.0, `F-OBS-07`) maps the same runtime events into
OpenTelemetry-style span JSON without adding mandatory OTel dependencies:

```java
import io.gavio.exporters.OtelSpanExporter;

Gateway gw = Gateway.builder()
    .exporter(new OtelSpanExporter(Path.of("otel-spans.jsonl"), "checkout-api"))
    .build();
```

## Self-hosted Control Plane

Control Plane support (v1.7.0) loads runtime config from an optional
self-hosted server and caches the last successful config for offline
fail-open/fail-closed behavior. v2.3.0 adds durable JSON file, SQLite, and
Postgres storage modes to the control-plane app.

```java
Gateway gw = Gateway.builder()
    .devMode(true)
    .controlPlane("http://127.0.0.1:8787", runtimeKey, "project:prod-support")
    .build();
```

Use `io.gavio.controlplane.ControlPlaneClient` directly when you need to inspect
or preload the fetched config before constructing a gateway.

## Ecosystem Integrations

Ecosystem integration helpers (v1.9.0, `F-INT-01`) provide dependency-light
metadata labels and compatibility rows for common gateways, observability
tools, eval tools, frameworks, and provider SDKs.

```java
import io.gavio.integrations.IntegrationCatalog;

var metadata = IntegrationCatalog.metadata(
    "langchain",
    Map.of("tenant", "acme", "feature", "support-chat", "environment", "prod"));
var rows = IntegrationCatalog.compatibilityMatrix();
```

## Platform Runtime Profile

Platform Runtime Profile support (v2.0.0, `F-PLAT-01`) summarizes production
readiness across runtime events, audit hashes, policy packs, cost governance,
tool runtime, and trust evidence without storing prompts or responses.

```java
import io.gavio.platform.PlatformRuntime;
import io.gavio.platform.PlatformRuntimeVerification;

Map<String, Object> profile = PlatformRuntime.builder("platform-prod-support")
    .generatedAt("2026-07-12T12:00:00Z")
    .runtime(Map.of(
        "environment", "production",
        "policySource", "project:prod-support",
        "eventExportMode", "metadata_only"))
    .surfaces(List.of(
        "runtime_events",
        "audit_hashes",
        "policy_packs",
        "cost_governance",
        "tool_runtime",
        "trust_evidence"))
    .evidence(Map.of(
        "auditChain", Map.of("recordCount", 42, "verified", true),
        "runtimeEvents", Map.of("eventCount", 168, "contentFree", true)))
    .build();

PlatformRuntimeVerification result = PlatformRuntime.verify(profile);
```

See [Platform Runtime Profile](../platform-runtime.md) for the schema,
readiness scoring contract, and cross-SDK test vector.

## Production Trust Package

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

```java
import io.gavio.trust.ProductionTrust;
import io.gavio.trust.ProductionTrustVerification;

Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
    .generatedAt("2026-07-12T12:00:00Z")
    .release("2.4.0", "v2.4.0", commit)
    .runtime("production", "project:prod-support", true, "metadata_only")
    .auditChain(recordCount, chainOk, headHash, tailHash)
    .build();

ProductionTrustVerification result = ProductionTrust.verify(bundle);
```

See [Production Trust Package](../trust-package.md) for the bundle schema,
threat model, privacy boundary, and cross-SDK examples.

## Prompt Registry + Evals

Prompt Registry + Evals (v1.4.0, `F-EVAL-01/02`) renders versioned chat
templates with metadata-only `PromptLineage` and runs deterministic eval cases
without storing raw model output in reports.

```java
import io.gavio.prompts.EvalAssertion;
import io.gavio.prompts.EvalCase;
import io.gavio.prompts.EvalReport;
import io.gavio.prompts.EvalSuite;
import io.gavio.prompts.PromptRegistry;
import io.gavio.prompts.PromptTemplate;
import io.gavio.types.Message;

PromptRegistry registry = new PromptRegistry();
registry.register(new PromptTemplate(
    "support.reply",
    "2026-07-12",
    List.of(
        Message.of("system", "You are concise."),
        Message.of("user", "Reply to {{ customer }} about {{ topic }}.")),
    List.of("customer", "topic"),
    Map.of()));

EvalSuite suite = new EvalSuite("support-smoke", List.of(new EvalCase(
    "refund",
    "support.reply",
    null,
    Map.of("customer", "Avery", "topic", "refund"),
    List.of(new EvalAssertion("contains", "refund", false)),
    Map.of())));

EvalReport report = suite.run(registry, (prompt, testCase) -> "Avery refund approved");
System.out.println(report.score());
```

Java v2.4.0 adds prompt-to-eval links, per-version regression gates, failure
triage metadata, and prompt release bundles for release evidence.

See [Prompt Registry + Evals](../prompt-registry-evals.md) for all SDKs and the
shared schemas.

## Embeddings

`gw.embed(List.of(texts...))` (`F-SEC-10`, since v0.9.0) runs embedding inputs
through the same interceptor pipeline as completions — PII is scanned and
redacted before the provider's embedding API is called; the response carries
one vector per input in `response.embeddings()`.

---

## Testing

```java
import io.gavio.testing.GavioTestKit;
import io.gavio.testing.MockProvider;
import io.gavio.interceptors.pii.PiiGuard;
import static io.gavio.testing.GavioAssertions.*;

var kit = GavioTestKit.builder()
    .interceptor(new PiiGuard())
    .provider(MockProvider.withResponse("I processed [IBAN_1]"))
    .build();

var result = kit.run(GavioRequest.builder()
    .message("user", "Transfer from NL91ABNA0417164300").build()).join();

assertPiiDetected(result, "IBAN");
assertNotContains(result.preRequestText(), "NL91ABNA0417164300");
assertAuditEntityType(result, "IBAN");
```

```bash
cd packages/gavio-java
mvn test                              # all modules, incl. shared vectors
mvn test -pl gavio-core,gavio-interceptor-pii   # a subset
```

---

## Notes

- **Java 17** minimum (records, sealed types, text blocks); **Java 21+**
  recommended. CI runs 17 and 21.
- All model types (`GavioRequest`, `GavioResponse`, `AuditRecord`, `PiiMatch`,
  `TokenUsage`) are immutable records — safe to share across threads.
- Async is `CompletableFuture<GavioResponse>` throughout; call `.join()` for a
  blocking result.
