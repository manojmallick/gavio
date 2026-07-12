# Gavio — Java SDK

> AI request runtime and inspector for production systems. PII protection,
> audit trails, runtime events, reliability, cost intelligence, policy packs,
> production trust packages, and provider adapters as composable interceptors.

`gavio` sits between your application and any LLM provider. The same request
passes through a pre/post interceptor chain — PII redaction, retries, caching,
budgets, audit logging, tool runtime, runtime events, runtime context — before and after the provider call. Same API in
[Python, Java, and JavaScript](https://github.com/manojmallick/gavio), enforced
by shared cross-SDK test vectors.

Part of the [Gavio](https://manojmallick.github.io/gavio) project. MIT licensed.

## Install

Multi-artifact Maven layout — depend only on what you need. `gavio-core` has
**zero mandatory runtime dependencies**.

```xml
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-core</artifactId>
  <version>1.9.0</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-pii</artifactId>
  <version>1.9.0</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-audit</artifactId>
  <version>1.9.0</version>
</dependency>
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-reliability</artifactId>
  <version>1.9.0</version>
</dependency>
```

Requires Java 17+. See the [module map](#module-map) for all artifacts.

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

## Tool Runtime

```java
import io.gavio.interceptors.toolruntime.ToolRuntimeInterceptor;

Gateway gw = Gateway.builder()
    .devMode(true)
    .use(ToolRuntimeInterceptor.builder().build())
    .build();
```

Tool Runtime reads `metadata("tools", ...)`, validates declared input/output
schemas, checks result freshness, detects configured conflicts, and records
provenance in `ctx.tools().get("runtime")`. Tool Runtime v2 also understands
`definitions`, `permissions`, `approvals`, `records`, and MCP metadata.

## Real providers

```java
import io.gavio.interceptors.audit.AuditInterceptor;
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

`OpenAiAdapter`, `GeminiAdapter`, `AzureOpenAiAdapter`, `OpenRouterAdapter`,
and `OllamaAdapter` work the same way — switching providers is a config change,
never an application change. Reliability policies implement `ExecutorPolicy`
and wrap the provider executor — the first registered is outermost.

Streaming buffers the provider stream (`F-REL-06`) so post-interceptors run on
the complete response — the publisher emits the fully processed content:

```java
Flow.Publisher<String> out = gw.stream(List.of(Message.of("user", "Hi")));
```

Embeddings run through the same pipeline — inputs are PII-scanned before the
provider's embedding API is called:

```java
GavioResponse resp = gw.embed(List.of("index this: contact jan@example.com")).join();
System.out.println(resp.embeddings().size());   // one vector per input, PII never left
```

## The Inspector

An embedded, zero-dependency visualizer for the pipeline: live traces,
per-interceptor waterfalls, PII redaction diffs, multi-agent call graphs,
replay, RED stats, and a read-only production dashboard.

```java
Gateway gw = Gateway.builder().devMode(true).inspect(true).build();
// open http://127.0.0.1:7411 and send a request
```

Capture modes: full (dev-mode default), redacted, and metadata (default
outside dev mode — no content, no replay). The `gavio inspect --store` CLI
for JSONL audit files is Python-only; the Java inspector serves the same
dashboard endpoints from its embedded server.

## Runtime export

```java
import io.gavio.exporters.JsonlRuntimeExporter;
import io.gavio.exporters.OtelSpanExporter;

Gateway gw = Gateway.builder()
    .devMode(true)
    .exporter(new JsonlRuntimeExporter(Path.of("runtime-events.jsonl")))
    .exporter(new OtelSpanExporter(Path.of("otel-spans.jsonl"), "checkout-api"))
    .build();
```

Runtime export (v1.1.0) writes metadata-safe JSONL events for integrations. The
exporter strips `messages`, `content`, and `diff` by default, even when the
local Inspector is in full capture mode. Observability + OTel (v1.3.0) maps
the same stream into OpenTelemetry-style span JSON (`F-OBS-07`).

## Self-hosted Control Plane

```java
Gateway gw = Gateway.builder()
    .devMode(true)
    .controlPlane("http://127.0.0.1:8787", runtimeKey, "project:prod-support")
    .build();
```

Control Plane support (v1.7.0) loads runtime config from an optional
self-hosted server, caches the last successful config, and can fail open or
closed during outages. The same surface is available through
`io.gavio.controlplane.ControlPlaneClient`.

## Ecosystem integrations

```java
import io.gavio.integrations.IntegrationCatalog;

var metadata = IntegrationCatalog.metadata(
    "langchain",
    Map.of("tenant", "acme", "feature", "support-chat", "environment", "prod"));
var rows = IntegrationCatalog.compatibilityMatrix();
```

Ecosystem integration helpers (v1.9.0, `F-INT-01`) provide dependency-light
metadata labels and compatibility rows for common gateways, observability
tools, eval tools, frameworks, and provider SDKs.

## Production Trust Package

```java
import io.gavio.trust.ProductionTrust;
import io.gavio.trust.ProductionTrustVerification;

Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
    .generatedAt("2026-07-12T12:00:00Z")
    .release("1.9.0", "v1.9.0", commit)
    .runtime("production", "project:prod-support", true, "metadata_only")
    .auditChain(recordCount, chainOk, headHash, tailHash)
    .build();

ProductionTrustVerification result = ProductionTrust.verify(bundle);
```

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

## Prompt Registry + Evals

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

Prompt Registry + Evals (v1.4.0) adds versioned prompt templates,
metadata-only lineage, deterministic pass/fail reports, and SHA-256 output
hashes instead of raw model output (`F-EVAL-01/02`).

## What's inside

Every feature is an interceptor you compose explicitly — no hidden magic.

- **Privacy & security** — `PiiGuard` with Email, IBAN (mod-97), BSN (11-proef),
  CreditCard (Luhn), Phone, IP, SSN scanners, redact/mask/tag/block + restore,
  and custom scanners via the `PiiScanner` SPI (`F-SEC-01`); `SecretScanner`
  (`F-SEC-04`); `PromptInjectionGuard` (`F-SEC-05`); embedding call guard
  (`F-SEC-10`); Policy Pack manifests for core, FinTech, custom regex-rule
  packs, and the signed domain catalog with load/override/signature APIs
  (`F-PACK-01/02/05`).
- **Reliability** — `RetryInterceptor` (`F-REL-01`), `FallbackChain`
  (`F-REL-02`), `CircuitBreaker` (`F-REL-03`), `LoadBalancer` (`F-REL-04`),
  buffered streaming (`F-REL-06`), `TimeoutPolicy` (`F-REL-07`).
- **Caching** — `SemanticCache`: SHA-256 exact + semantic (cosine) cache with
  in-memory and Redis backends (`F-CACHE-01/02/03/04`).
- **Cost & governance** — per-request cost tracking via `PricingProvider`
  (`F-GOV-01`), `CostControl` budget caps (`F-GOV-02`), `RateLimiter`
  (`F-GOV-03`), `ModelPolicy` (`F-GOV-04`), `CostRouter` (`F-GOV-06`), Cost
  Governance v2 budget policies, decisions, and reports (v1.2.0).
- **Observability** — `AuditInterceptor` with SHA-256 content hashes, never raw
  text (`F-OBS-01`), tamper-evident hash chain (`F-OBS-02`), multi-agent DAG
  tracing via `agentId`/`parentTraceId` (`F-OBS-03`), prompt lineage
  (`F-OBS-04`), `MetricsInterceptor` Prometheus metrics (`F-OBS-08`),
  `StdoutSink`.
- **Prompt Registry + Evals** — `PromptRegistry`, `PromptTemplate`, and
  `EvalSuite` in `io.gavio.prompts` (`F-EVAL-01/02`).
- **Runtime export** — metadata-safe JSONL runtime events (`F-EXP-01`) and
  OpenTelemetry-style span JSON (`F-OBS-07`) for gateway, observability, and
  eval integrations.
- **Control Plane** — optional self-hosted runtime config with policy rollout,
  budget config, audit search, config snapshots, SDK cache fallback, and
  `io.gavio.controlplane.ControlPlaneClient` (v1.7.0).
- **Production Trust Package** — metadata-only release evidence bundles with
  deterministic hashes, privacy checks, audit-chain evidence, runtime-event
  evidence, and document/control pointers (`F-TRUST-01`).
- **Quality** — `GuardrailsInterceptor` with `JsonSchemaValidator` and regex
  validators (`F-QUA-01/02`), composite `RiskScorer` (`F-QUA-06`).
- **Inspector** — dev-time visualizer (`F-DX-09/10`), agent call graphs and
  session views (`F-OBS-10`), trace replay (`F-DX-11`), PII-sanitized
  test-case export (`F-DX-12`), read-only production dashboard (`F-DX-08`).
- **Developer experience** — dev mode (`F-DX-01`), dry-run (`F-DX-02`),
  `GavioTestKit` + `MockProvider` + `GavioAssertions` in `gavio-testing`
  (`F-DX-03`), `GavioOpenAI` drop-in shim (`F-DX-04`).
- **Providers** — OpenAI, Anthropic, Gemini, Azure OpenAI, OpenRouter, Ollama,
  Mock — all over `java.net.http.HttpClient`.

See the [documentation site](https://manojmallick.github.io/gavio), the
[Java guide](../../docs/packages/java.md), the runnable
[examples](../../examples/), and the [CHANGELOG](../../CHANGELOG.md) for
version-by-version detail.

## Build & test

```bash
mvn test              # JUnit 5 suite, all modules
```

## Module map

All artifacts share the `io.github.manojmallick` group id and version `1.9.0`.

| Artifact | Contains |
|---|---|
| `gavio-core` | Gateway, request/response model, interceptor chain, Tool Runtime, Production Trust Package, pricing, inspector, OpenAI shim, zero-dep JSON |
| `gavio-interceptor-pii` | PiiGuard, PiiScanner SPI, built-in scanners, SecretScanner, PromptInjectionGuard |
| `gavio-interceptor-audit` | AuditInterceptor, AuditSink, AuditRecord, hash chain, StdoutSink |
| `gavio-interceptor-reliability` | RetryInterceptor, FallbackChain, CircuitBreaker, LoadBalancer, TimeoutPolicy |
| `gavio-interceptor-cache` | SemanticCache, memory/Redis backends, vector backends |
| `gavio-interceptor-governance` | CostControl, RateLimiter, ModelPolicy, CostRouter, BudgetPolicyControl, CostGovernanceReport |
| `gavio-interceptor-guardrails` | GuardrailsInterceptor, JsonSchemaValidator, regex validators |
| `gavio-interceptor-metrics` | MetricsInterceptor, PrometheusMetrics |
| `gavio-interceptor-quality` | RiskScorer |
| `gavio-provider-openai` / `-anthropic` / `-gemini` / `-azure` / `-openrouter` / `-ollama` | Provider adapters |
| `gavio-testing` | GavioTestKit, MockProvider, GavioAssertions, Fixtures |
