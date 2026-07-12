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
- [Testing](#testing)
- [Notes](#notes)

---

## Install

**Maven**
```xml
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-core</artifactId>
  <version>1.2.0</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-pii</artifactId>
  <version>1.2.0</version>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
implementation("io.github.manojmallick:gavio-core:1.2.0")
implementation("io.github.manojmallick:gavio-interceptor-pii:1.2.0")
```

> The Maven **groupId** is `io.github.manojmallick`; the Java **package** in
> source is `io.gavio.*` (they don't need to match).

---

## Artifacts

| Artifact | Contains |
|---|---|
| `gavio-core` | Gateway, request/response records, interceptor chain, Tool Runtime, providers base, Mock |
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
`.devMode(true)`, `.dryRun(true)`.

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

### Tool Runtime (v0.14.0)

`ToolRuntimeInterceptor` validates tool metadata from request `metadata("tools",
...)` before tool outputs re-enter model context. It supports declared
input/output schemas, freshness/TTL checks, conflict detection across configured
result keys, confidence scoring, and provenance records under
`ctx.tools().get("runtime")`.

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
| Ollama | `OLLAMA` | — (local; `OLLAMA_HOST`) |
| Mock | dev mode / `MockProvider` | — |

Gemini, Azure OpenAI, and Ollama were added in **v0.2.0** (add the matching
`gavio-provider-*` artifact).

Adapters use `java.net.http.HttpClient` (async `sendAsync`) with a hand-rolled
JSON reader/writer — no external JSON dependency.

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
