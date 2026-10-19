---
description: "Gavio Java SDK guide — Maven artifacts, Gateway API, interceptors, providers, testing. Java 17+, CompletableFuture async, zero mandatory dependencies."
---

# Java SDK (`io.github.manojmallick:gavio-*`)

> Maven Central · Java 17+ · immutable records + builders · `CompletableFuture`
> async · zero mandatory dependencies

Source: [`packages/gavio-java`](https://github.com/manojmallick/gavio/tree/main/packages/gavio-java). Multi-artifact so you
pull only what you need.

- [Install](#install)
- [Artifacts](#artifacts)
- [Gateway API](#gateway-api)
- [Interceptors](#interceptors)
- [Providers](#providers)
- [Ecosystem Integrations](#ecosystem-integrations)
- [Platform Runtime Profile](#platform-runtime-profile)
- [Production Trust Package](#production-trust-package)
- [Prompt Registry + Evals](#prompt-registry--evals)
- [Platform Workflow Release](#platform-workflow-release)
- [Testing](#testing)
- [Notes](#notes)

---

## Install

**Maven**
```xml
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-core</artifactId>
  <version>3.2.1</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-pii</artifactId>
  <version>3.2.1</version>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
implementation("io.github.manojmallick:gavio-core:3.2.1")
implementation("io.github.manojmallick:gavio-interceptor-pii:3.2.1")
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
(first-registered = outermost). See [interceptors.md](./interceptors.md).

### Loop guard (F-REL-09)

`LoopGuard` stops a runaway agent. It counts the provider calls of one **root run** and, before
each call, compares the run with limits: refuse the call, reroute it to a cheaper model, or only
log the breach. Same behaviour as the Python and JavaScript SDKs, pinned by the shared vectors.

```java
import io.gavio.GavioException.LoopGuardException;
import io.gavio.interceptors.reliability.LoopGuard;
import io.gavio.interceptors.reliability.LoopGuardPolicy;

LoopGuard guard = LoopGuard.of(LoopGuardPolicy.builder("support-agent")
    .maxSteps(25)                  // at most 25 provider calls in one run
    .maxCostUsd(1.00)              // stop once $1.00 of spend is recorded
    .maxWallTimeSeconds(120)       // from the run's first call
    .maxRepeatedToolCalls(3)       // same tool with the same arguments, three times
    .action(LoopGuardPolicy.Action.STOP)   // or FALLBACK (with .fallbackModel(...)) or DRY_RUN
    .build());
Gateway gw = Gateway.builder().provider(Provider.OPENAI).use(new AuditInterceptor()).use(guard).build();

GavioRequest.Builder next = GavioRequest.builder()
    .message("user", "go").model("gpt-4o").provider(Provider.OPENAI);
GavioResponse root = gw.complete(next.agentId("planner").build()).join();
try {
    while (true) {   // LoopGuardException on the 26th call, before it reaches the provider
        gw.complete(next.parentTraceId(root.traceId()).build()).join();
    }
} catch (CompletionException e) {
    if (e.getCause() instanceof LoopGuardException stop) {
        System.out.println(stop.decision().get("breached"));   // max_steps
    }
}
```

A run is `metadata.runId` when you set one, else the root of the `parentTraceId` chain, else the
call's own trace id — an agent loop has to pass one of the first two, because calls with neither
are separate runs that no limit can link. Each limit is optional and is checked *before* a call:
`maxSteps(25)` allows 25 calls; cost is only known afterwards, so the call that crosses
`maxCostUsd` still happens and the next one is refused. A repeated tool call is the same tool name
and the same arguments (hashed, key order ignored) requested `maxRepeatedToolCalls` times in the
run. A call that fails at the provider still counts as a step. `STOP` fails the call with a
`LoopGuardException` (`stop.decision()` has the verdict: which limit, steps, cost, elapsed time —
never prompts or tool arguments); `FALLBACK` lets the call through on `fallbackModel`; `DRY_RUN`
only records, and a gateway `dryRun(true)` never stops or reroutes either.

Each guarded call's `AuditRecord` gets a `loopGuard()` entry (written as `loop_guard`: policy id,
run id, action, the limit reached, steps, cost, elapsed seconds — counts only), omitted when no
guard is installed so hash chains written before it still verify; breaches also show in the
Inspector. State is in memory per guard and bounded
(`new LoopGuardStore(10_000, 3600, clock)`: least recently used runs are evicted, idle runs
forgotten). `LoopGuard.evaluate(...)`, `LoopGuardState` and `io.gavio.RootRunResolver` are the
pure parts. Semantics: `spec/README.md` ('Loop guard').

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

Gemini, Azure OpenAI, and Ollama were added in **v0.2.0** (add the matching
`gavio-provider-*` artifact).

Adapters use `java.net.http.HttpClient` (async `sendAsync`) with a hand-rolled
JSON reader/writer — no external JSON dependency.

### Tool calling (F-ADP-03)

OpenAI, Azure OpenAI, OpenRouter, Anthropic, Gemini, and Ollama share one tool-calling
shape. Declare `tools` (the same `ToolDefinition` maps Tool Runtime uses), read
`response.toolCalls()`, and answer with tool-role messages — the adapter translates to and
from each provider's native format, parallel calls included.

```java
List<Map<String, Object>> tools = List.of(Map.of(
    "name", "get_weather",
    "description", "Current weather",
    "inputSchema", Map.of(
        "type", "object",
        "properties", Map.of("city", Map.of("type", "string")),
        "required", List.of("city"))));
List<Message> messages = new ArrayList<>(List.of(Message.of("user", "Weather in Paris and Rome?")));

GavioResponse response = gw.completeWithTools(messages, tools, ToolChoice.auto()).join();
while (!response.toolCalls().isEmpty()) {          // parallel calls arrive together
    messages.add(response.toAssistantMessage());
    for (ToolCall call : response.toolCalls()) {   // ToolCall(id, name, arguments)
        messages.add(call.result(runTool(call.name(), call.arguments())).toMessage());
    }
    response = gw.completeWithTools(messages, tools, null).join();
}
```

`GavioRequest.builder().tools(...).toolChoice(...)` does the same when you need the other
request options. `ToolChoice` is `auto()`, `none()`, `required()`, or `tool("get_weather")`
(the builder also takes the wire shapes `"auto"`, `"none"`, `"required"`, `Map.of("name", ...)`);
`response.finishReason()` is a normalised `FinishReason`. Gemini and Ollama send no call ids,
so Gavio assigns `call_<index>_<name>`. `Message` carries the spec's camelCase members
(`toolCalls()`, `toolCallId()`, `name()`, `isError()`); `Message.from(map)` reads those keys and
Python's `tool_calls` / `tool_call_id` / `is_error` spellings.

Privacy applies to tool traffic too: `PiiGuard` and the secret scanner cover tool-call
arguments and tool-role messages before the provider payload is built, and audit records keep
only the tool name and an arguments hash (`AuditRecord.toolCalls()`, written as
`tool_calls[].arguments_hash` and left out when the response had no tool calls). To validate
what the model asked for, pass the calls to Tool Runtime:
`ToolRuntimeInterceptor.analyzeProviderToolCalls(response.toolCalls(), toolsContext,
ProviderCallOptions.builder().definitions(tools))`. Cached responses never replay a tool call,
and requests that declare `tools` skip the semantic cache level.

The pure translators are public static methods on each adapter — `toProviderPayload(request)`
and `fromProviderResponse(map)` on `OpenAiAdapter`, `AzureOpenAiAdapter`, `OpenRouterAdapter`,
`AnthropicAdapter`, `GeminiAdapter`, and `OllamaAdapter` — with no network involved. The
Chat Completions mapping the first three share lives in `gavio-core`, so none of those modules
depends on another. A provider tool call whose arguments are not a JSON object raises
`MalformedToolCallException`; its message names the tool, never the arguments. Arguments are
hashed over canonical JSON, so keep integers and strings in arguments you intend to audit: an
integral float such as `1.0` hashes as `1`, which Python spells `1.0`.

Not yet supported: streaming tool-call deltas (`gw.stream()` throws a `ConfigurationException`
if you pass `tools`, rather than dropping them), and provider-opaque per-call state such as
Gemini thought signatures. The Ollama mapping follows its documented `/api/chat` shape and has
not been exercised against a live server; Ollama has no tool choice, so only `auto` is
accepted there.

---

## Inspector

Enable the embedded pipeline visualizer (`F-DX-09/10`, off by default) and open
`http://127.0.0.1:7411` — live traces, waterfalls, PII diffs, agent call
graphs, replay, stats. Full guide: [Inspector](./inspector.md).

```java
Gateway gw = Gateway.builder().devMode(true).inspect(true).build();
```

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

## Self-hosted Control Plane

Control Plane support (v1.7.0) loads runtime config from an optional
self-hosted server and caches the last successful config for offline
fail-open/fail-closed behavior. v2.3.0 adds durable JSON file, SQLite, and
Postgres storage modes to the control-plane app. v2.6.0 adds Enterprise Admin
v2 controls in the app while keeping the SDK runtime config contract unchanged.

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
tools, eval tools, frameworks, and provider SDKs. Ecosystem adapter helpers
(v2.5.0, `F-INT-02`) add metadata-only payload fragments for LiteLLM,
promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and the Vercel AI SDK.

```java
import io.gavio.integrations.IntegrationCatalog;
import io.gavio.integrations.IntegrationAdapters;

var metadata = IntegrationCatalog.metadata(
    "langchain",
    Map.of("tenant", "acme", "feature", "support-chat", "environment", "prod"));
var rows = IntegrationCatalog.compatibilityMatrix();
var adapter = IntegrationAdapters.payload(
    "langchain",
    Map.of("traceId", "trace_123", "data", Map.of("status", "ok", "provider", "openai")),
    Map.of("tenant", "acme", "feature", "support-chat", "prompt", "raw prompt text"));
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

See [Platform Runtime Profile](./platform-runtime.md) for the schema, readiness
scoring contract, and cross-SDK test vector.

## Production Trust Package

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

```java
import io.gavio.trust.ProductionTrust;
import io.gavio.trust.ProductionTrustVerification;

Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
    .generatedAt("2026-07-12T12:00:00Z")
    .release("3.2.1", "v3.2.1", commit)
    .runtime("production", "project:prod-support", true, "metadata_only")
    .auditChain(recordCount, chainOk, headHash, tailHash)
    .build();

ProductionTrustVerification result = ProductionTrust.verify(bundle);
```

See [Production Trust Package](./trust-package.md) for the bundle schema,
threat model, privacy boundary, and cross-SDK examples.

## Prompt Registry + Evals

Prompt Registry + Evals (v1.4.0, `F-EVAL-01/02`) renders versioned chat
templates with metadata-only `PromptLineage` and runs deterministic eval cases
without storing raw model output in reports.

```java
PromptRegistry registry = new PromptRegistry();
registry.register(new PromptTemplate(
    "support.reply",
    "2026-07-12",
    List.of(
        Message.of("system", "You are concise."),
        Message.of("user", "Reply to {{ customer }} about {{ topic }}.")),
    List.of("customer", "topic"),
    Map.of()));

EvalReport report = new EvalSuite("support-smoke", List.of(new EvalCase(
    "refund",
    "support.reply",
    null,
    Map.of("customer", "Avery", "topic", "refund"),
    List.of(new EvalAssertion("contains", "refund", false)),
    Map.of()))).run(registry, (prompt, testCase) -> "Avery refund approved");
```

Java v2.4.0 adds prompt-to-eval links, per-version regression gates, failure
triage metadata, and prompt release bundles for release evidence.

## Platform Workflow Release

The platform workflow release (`F-DX-13`, Python since v3.0.0) is ported to
Java: one metadata-only artifact joins prompt release bundles, eval reports,
policy-pack signature evidence, production trust verification, and
platform-runtime readiness. For the same inputs the artifact and its
`workflowHash` are identical to the Python reference.

```java
import io.gavio.platform.PlatformWorkflow;
import io.gavio.platform.PlatformWorkflowResult;

PlatformWorkflowResult result = PlatformWorkflow.runFile(Path.of("workflow.json"));
System.out.println(result.passed() + " " + result.artifact().get("workflowHash"));
```

`PlatformWorkflow.build(manifest, basePath)` builds the same artifact from an
in-memory manifest, and `io.gavio.prompts.EvalRunner` is the library eval runner
the workflow uses. Policy Pack evidence is read through the
`PlatformWorkflowPolicyResolver` SPI, which `gavio-interceptor-pii` provides, so
keep that artifact on the classpath (or pass a resolver). The `gavio workflow
release` CLI and YAML manifests stay Python-only; Java reads JSON documents.

See [Platform Workflow Release](./platform-workflow-release.md) for the manifest format, the canonical
hash definition, and the shared test vector.

## Embeddings

`gw.embed(List.of(texts...))` (`F-SEC-10`, since v0.9.0) runs embedding inputs
through the same interceptor pipeline as completions — PII is scanned and
redacted before the provider's embedding API is called; one vector per input
in `response.embeddings()`.

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
