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
  <version>0.8.0</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-pii</artifactId>
  <version>0.8.0</version>
</dependency>
```

**Gradle (Kotlin DSL)**
```kotlin
implementation("io.github.manojmallick:gavio-core:0.8.0")
implementation("io.github.manojmallick:gavio-interceptor-pii:0.8.0")
```

> The Maven **groupId** is `io.github.manojmallick`; the Java **package** in
> source is `io.gavio.*` (they don't need to match).

---

## Artifacts

| Artifact | Contains |
|---|---|
| `gavio-core` | Gateway, request/response records, interceptor chain, providers base, Mock |
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
