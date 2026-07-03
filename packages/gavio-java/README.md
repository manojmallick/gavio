# Gavio — Java SDK

> The open standard AI gateway for production systems. PII protection, audit
> trails, reliability, and cost control as composable interceptors.

`gavio` sits between your application and any LLM provider. The same request
passes through a pre/post interceptor chain — PII redaction, retries, cost
tracking, audit logging — before and after the provider call.

Part of the [Gavio](https://gavio.io) project. MIT licensed.

## Install

Multi-artifact Maven layout — depend only on what you need. `gavio-core` has
**zero mandatory runtime dependencies**.

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
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-audit</artifactId>
  <version>0.8.0</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-reliability</artifactId>
  <version>0.8.0</version>
</dependency>
```

Requires Java 17+ (records, sealed types, text blocks).

## Quick start (dev mode — no API key, no network)

`devMode(true)` wires a `MockProvider` plus, when `gavio-interceptor-audit` is on
the classpath, a stdout audit interceptor (auto-discovered via `ServiceLoader`).

```java
import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.pii.PiiGuard;

Gateway gw = Gateway.builder()
    .devMode(true)              // MockProvider + stdout audit
    .use(new PiiGuard())        // redact PII before it leaves the process
    .build();

GavioResponse resp = gw.complete(
    GavioRequest.builder()
        .message("user", "Email jan@example.com about NL91ABNA0417164300")
        .agentId("demo")
        .build()
).join();

System.out.println(resp.content());                 // PII restored in the reply
System.out.printf("cost=$%.6f latency=%dms%n", resp.costUsd(), resp.latencyMs());
System.out.println("pii types: " + ((AuditRecord) resp.audit()).piiEntityTypes());
```

All gateway calls return `CompletableFuture<GavioResponse>`; use `.join()`,
`.get()`, or compose with `thenApply(...)`.

## Real providers

```java
import io.gavio.Gateway;
import io.gavio.interceptors.audit.AuditInterceptor;
import io.gavio.interceptors.pii.PiiGuard;
import io.gavio.interceptors.reliability.RetryInterceptor;
import io.gavio.interceptors.reliability.TimeoutPolicy;
import io.gavio.providers.anthropic.AnthropicAdapter;
import io.gavio.types.Sensitivity;

Gateway gw = Gateway.builder()
    .adapter(AnthropicAdapter.builder()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .timeoutSeconds(30)
        .build())
    .model("claude-sonnet-4-6")
    .use(PiiGuard.builder().sensitivity(Sensitivity.STRICT).build())
    .use(AuditInterceptor.builder().build())          // stdout sink
    .use(TimeoutPolicy.builder().timeoutSeconds(30).build())
    .use(RetryInterceptor.builder().maxAttempts(3).build())
    .build();

GavioResponse resp = gw.complete(
    GavioRequest.builder().message("user", "Hi").build()).join();
```

`OpenAiAdapter` (`gavio-provider-openai`) works the same way and reads
`OPENAI_API_KEY`. Reliability policies (`RetryInterceptor`, `TimeoutPolicy`,
`FallbackChain`) implement `ExecutorPolicy` and wrap the provider executor —
the first registered is outermost.

## Custom scanner

```java
import io.gavio.interceptors.pii.*;
import java.util.List;
import java.util.regex.*;

public class IngAccountScanner implements PiiScanner {
    private static final Pattern P = Pattern.compile("\\bNL\\d{2}INGB\\d{10}\\b");

    public String entityType() { return "ING_ACCOUNT"; }

    public List<PiiMatch> scan(String text, ScanContext ctx) {
        var out = new java.util.ArrayList<PiiMatch>();
        Matcher m = P.matcher(text);
        while (m.find()) {
            out.add(PiiMatch.builder()
                .entityType(entityType()).start(m.start()).end(m.end())
                .value(m.group())
                .replacement("[ING_ACCOUNT_" + ctx.nextIndex(entityType()) + "]")
                .build());
        }
        return out;
    }
}
```

## What ships in v0.1.0

- **Core** (`gavio-core`) — `Gateway` fluent builder, `InterceptorChain`,
  `GavioRequest` / `GavioResponse` records, UUID v7 monotonic `traceId`,
  `Message`, `TokenUsage`, `PricingProvider`, `ExecutorPolicy` SPI, zero-dep JSON.
- **PII Guard** (`gavio-interceptor-pii`, F-SEC-01) — Email, IBAN (ISO 13616
  mod-97), BSN (11-proef), CreditCard (Luhn), Phone, IP (v4/v6), SSN scanners,
  redact / mask / tag / block, restore-on-response, greedy overlap resolution,
  per-sensitivity confidence floors.
- **Secret Scanner** (F-SEC-04) — API keys, JWTs, PEM keys, DB connection strings.
- **Reliability** (`gavio-interceptor-reliability`) — retry with capped
  exponential backoff + jitter (F-REL-01), fallback chain (F-REL-02),
  timeout (F-REL-07).
- **Cost tracking** (F-GOV-01) — per-request `costUsd` via `PricingProvider`.
- **Audit** (`gavio-interceptor-audit`, F-OBS-01) — `AuditRecord` with SHA-256
  prompt/response hashes (metadata only, never content) + `StdoutSink` (F-OBS-05).
- **Dev mode** (F-DX-01) and **dry-run mode** (F-DX-02).
- **Providers** — OpenAI, Anthropic (over `java.net.http.HttpClient`), Mock.
- **Testing** (`gavio-testing`) — `GavioTestKit`, `MockProvider`,
  `GavioAssertions`, synthetic `Fixtures`.

Planned for v0.2.0+: semantic cache, guardrails, governance, Spring Boot
starter, `RemoteNerScanner`, and the Gemini / Azure / Ollama providers.

See the [Java guide](../../docs/packages/java.md) for the full API reference.

## Build & test

```bash
mvn -q compile        # all modules
mvn test              # JUnit 5 suite (50 tests)
```

## Module map

| Artifact | Contains | Version |
|---|---|---|
| `gavio-core` | Gateway, request/response model, interceptor chain, pricing, JSON | `0.1.0` |
| `gavio-interceptor-pii` | PiiGuard, PiiScanner, built-in scanners | `0.1.0` |
| `gavio-interceptor-audit` | AuditInterceptor, AuditSink, AuditRecord, StdoutSink | `0.1.0` |
| `gavio-interceptor-reliability` | RetryInterceptor, FallbackChain, TimeoutPolicy | `0.1.0` |
| `gavio-provider-openai` | OpenAI adapter | `0.1.0` |
| `gavio-provider-anthropic` | Anthropic adapter | `0.1.0` |
| `gavio-testing` | GavioTestKit, MockProvider, GavioAssertions | `0.1.0` |
