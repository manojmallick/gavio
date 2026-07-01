# Changelog

All notable changes to Gavio are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Feature IDs reference the [Feature Registry](./MASTER_PLAN.md#4-feature-registry) in MASTER_PLAN.md.

---

## [Unreleased]

Changes in active development, not yet released.

### Added
- **Python SDK — v0.1.0 Foundation implementation** (`packages/gavio-py`). First
  working reference implementation of the gateway. Zero mandatory runtime
  dependencies; Python 3.10+.
  - Core: `Gateway` fluent builder, `InterceptorChain` (onion pre/post model),
    `GavioRequest` / `GavioResponse`, `InterceptorContext`, UUID v7 (monotonic)
    `trace_id`, `agent_id` / `parent_trace_id` fields.
  - `F-SEC-01` PII Guard regex tier — `EmailScanner`, `IbanScanner` (ISO 13616
    mod-97), `BsnScanner` (11-proef), `CreditCardScanner` (Luhn),
    `PhoneScanner`, `IpAddressScanner` (IPv4/IPv6), `SsnScanner`; redact / mask /
    tag / block modes; restore-on-response; overlap resolution; entity-type
    logging (never raw values).
  - `F-SEC-04` `SecretScanner` — API keys, AWS keys, GitHub tokens, JWTs, PEM
    private keys, DB connection strings.
  - `F-REL-01` `RetryInterceptor` (exponential backoff + jitter),
    `F-REL-02` `FallbackChain`, `F-REL-07` `TimeoutPolicy` — composed around the
    provider call via the `ExecutorPolicy` base.
  - `F-GOV-01` Token cost tracking — `PricingProvider`, `cost_usd` on every
    response.
  - `F-OBS-01` `AuditInterceptor` + `AuditRecord` (SHA-256 prompt/response
    hashes, metadata only), `F-OBS-05` `StdoutSink`.
  - `F-DX-01` dev mode (auto-wires `MockProvider` + stdout audit),
    `F-DX-02` dry-run mode.
  - Provider adapters: `OpenAIAdapter`, `AnthropicAdapter` (stdlib HTTP, no SDK
    dependency), `MockProvider`.
  - `MemoryBackend` cache substrate; `GavioTestKit` + synthetic fixtures.
  - 37 unit tests passing; ruff clean.
- **JavaScript/TypeScript SDK — v0.1.0 Foundation** (`packages/gavio-js`). Full
  parity with the Python reference; zero runtime dependencies; ESM; Node.js 18+.
  - Core: `Gateway` (object config + `.use()` / `.withAdapter()`),
    `InterceptorChain`, `GavioRequest` / `GavioResponse` (camelCase), monotonic
    UUID v7 `traceId`, error hierarchy, factory-function interceptors.
  - `F-SEC-01` PII Guard regex tier + `F-SEC-04` secret scanner (same 8
    scanners, checksums, redact/mask/tag/block, restore, overlap resolution).
  - `F-REL-01/02/07` retry / fallback / timeout via the `ExecutorPolicy` model.
  - `F-GOV-01` cost tracking, `F-OBS-01/05` audit + stdout sink (SHA-256 via
    `node:crypto`), `F-DX-01/02` dev + dry-run modes.
  - Providers: OpenAI, Anthropic (native `fetch`), Mock. `GavioTestKit`.
  - 59 unit tests passing; `tsc --noEmit` strict clean.
- **Java SDK — v0.1.0 Foundation** (`packages/gavio-java`). Maven multi-module
  (`gavio-core`, `-interceptor-pii`, `-interceptor-audit`,
  `-interceptor-reliability`, `-provider-openai`, `-provider-anthropic`,
  `-testing`); Java 17 target; zero runtime dependencies (hand-rolled JSON).
  - Immutable records + builders; `CompletableFuture<GavioResponse>` async;
    monotonic thread-safe UUID v7; `ExecutorPolicy` composition.
  - Same feature set: PII Guard + secret scanner, retry/fallback/timeout, cost
    tracking, audit + stdout sink (`MessageDigest` SHA-256), dev + dry-run.
  - 50 tests passing across modules (`mvn test` → BUILD SUCCESS).
- All three SDKs verified to emit identical UUID v7 trace-id format and audit
  log lines (cross-SDK parity).
- **Canonical spec** (`spec/`) — JSON Schema (Draft 2020-12) for `GavioRequest`,
  `GavioResponse`, `AuditRecord`, `PiiMatch`, `InterceptorResult`, plus a README
  documenting the camelCase wire format and Python snake_case mapping.
- **Shared cross-SDK test vectors** (`test-vectors/pii/`) — `checksums.json`
  (single-scanner regex/checksum cases) and `detection.json` (full-guard
  detection cases), verified against the Python reference. Each SDK loads and
  runs them in its own suite (Python `test_vectors.py`, JS `test-vectors.test.ts`,
  Java `TestVectorsTest`), so a parity regression in any language fails its CI.
- **CI** (`.github/workflows/ci.yml`) — runs all three SDK suites (Python 3.10–3.12,
  Node 18/20/22, Java 17/21), including the shared vectors, on every push and PR.

### Changed
- Nothing yet.

---

## [0.1.0] — TBD

### Summary
Foundation release. Working interceptor pipeline with provider adapters,
PII Guard (regex tier), audit logging, retry/fallback, and dev mode.
Ships across Python, Java, and JavaScript simultaneously.

### Added

#### Core
- `Gateway` class with fluent builder API (Python, Java, JavaScript)
- `InterceptorChain` — pre/post interceptor pipeline
- `GavioRequest` / `GavioResponse` — canonical request/response model
- `ScanContext` — per-request context for interceptors
- `trace_id` (UUID v7) assigned to every request
- `agent_id` and `parent_trace_id` fields on every request
- `dev_mode` flag — runs entirely in-process, no network, no API key needed
- `dry_run` flag — interceptors log but never block or modify

#### PII Guard (`F-SEC-01`) — all three languages
- `PiiScanner` interface — extensible scanner API with `scan()`, `tier`, `entity_type`
- `ScannerRegistry` — register/discover scanners at runtime
- `PiiMatch` — typed match result with start, end, value, replacement, confidence
- Built-in scanners: `EmailScanner`, `IbanScanner`, `BsnScanner`, `CreditCardScanner`, `PhoneScanner`, `IpAddressScanner`, `SsnScanner`
- Sensitivity levels: `strict`, `balanced`, `permissive`
- Modes: `redact`, `mask`, `tag`, `block`
- Restore-on-response: replaces tokens back with originals in the response
- Entity type + count logging (never logs raw PII values)

#### Secret Scanner (`F-SEC-04`) — all three languages
- `SecretScanner` — detects API keys (OpenAI `sk-...`, Anthropic `sk-ant-...`), AWS `AKIA...`, GitHub tokens, JWTs, PEM private keys, database connection strings

#### Retry & Fallback (`F-REL-01`, `F-REL-02`) — all three languages
- `RetryInterceptor` — exponential backoff with jitter, configurable max attempts
- `FallbackChain` — sequential provider fallback on failure
- `TimeoutPolicy` — per-request timeout enforcement (`F-REL-07`)

#### Cost Tracking (`F-GOV-01`) — all three languages
- Real-time cost estimation per request
- Provider pricing tables for OpenAI, Anthropic (updated via config)
- `cost_usd` field on every `GavioResponse`

#### Audit Interceptor (`F-OBS-01`) — all three languages
- `AuditInterceptor` — captures full request/response metadata
- `AuditRecord` — immutable record: trace_id, provider, model, token usage, cost, latency, PII entity types/counts, interceptors fired, cache hit, risk score, lineage
- `AuditSink` — extensible sink interface with `write(AuditRecord)` method
- `StdoutSink` — human-readable output for development (`F-OBS-05`)
- Schema version `"1.0"` on every `AuditRecord`

#### Provider Adapters
- `OpenAIAdapter` — GPT-4o, o1, embeddings. Streaming supported.
- `AnthropicAdapter` — Claude Sonnet, Haiku, Opus. Streaming supported.

#### Developer Experience
- Local dev mode (`F-DX-01`) — `MockProvider`, `MemoryCacheBackend`, `StdoutSink` wired automatically
- Dry-run mode (`F-DX-02`) — all interceptors log without modifying requests

#### Python-specific
- `gavio` package on PyPI with zero mandatory dependencies
- `gavio[presidio]`, `gavio[redis]`, `gavio[otel]`, `gavio[elasticsearch]`, `gavio[all]` extras
- Async-first with `complete()` / `complete_sync()` sync wrapper
- Full PEP 484 type hints, `py.typed` marker, mypy strict-mode compatible
- Supports Python 3.10, 3.11, 3.12

#### Java-specific
- Multi-artifact Maven structure: `gavio-core`, `gavio-interceptor-pii`, `gavio-interceptor-audit`, `gavio-interceptor-reliability`
- `CompletableFuture<GavioResponse>` async API
- Java 17+ required (uses records, sealed interfaces)
- Java 21 virtual thread support for non-blocking I/O
- `gavio-spring-boot-starter` — auto-configuration, health indicator, Micrometer metrics
- All artifacts published to Maven Central under `io.gavio` groupId

#### JavaScript-specific
- `gavio` package on npm — ESM + CJS dual build
- Full TypeScript 5.0+ type definitions included
- Zero mandatory dependencies
- Sub-path imports for tree-shaking (`gavio/interceptors/pii`, etc.)
- Node.js 18+, Deno 1.40+, Bun 1.0+ support
- `edgeMode: true` for Cloudflare Workers / Vercel Edge

### Notes
- APIs may change before v0.2.0. Semver stability guarantee begins at v0.2.0.

---

## [0.2.0] — TBD _(planned)_

### Summary
Production-ready release. Semantic caching, multi-agent DAG tracing, hash-chain
audit records, NER-based PII scanning, circuit breaker, and guardrails.

### Planned additions

#### PII Guard — NER tier (`F-SEC-02`)
- `PresidioAdapterScanner` (Python) — wraps Microsoft Presidio + spaCy
- `RemoteNerScanner` (Java, JavaScript) — calls Presidio REST sidecar
- `HuggingFaceNerScanner` (Python) — configurable transformer model

#### Prompt Injection Defense (`F-SEC-05`)
- `PromptInjectionGuard` interceptor — pattern + semantic similarity detection
- Ships with a curated injection attack corpus

#### Reliability (`F-REL-03`, `F-REL-04`, `F-REL-06`)
- `CircuitBreaker` — open/half-open/closed state machine
- `LoadBalancer` — weighted round-robin + latency-aware routing
- `StreamBuffer` — buffer streaming response before post-interceptors run

#### Caching (`F-CACHE-01`, `F-CACHE-02`, `F-CACHE-03`, `F-CACHE-04`)
- `SemanticCache` interceptor with two-level cache
- `MemoryCacheBackend` — default zero-dependency dev backend
- `RedisCacheBackend` — production distributed cache
- SHA-256 exact cache: microsecond-latency exact match
- Semantic cache: cosine similarity on embeddings, configurable threshold

#### Governance (`F-GOV-02`, `F-GOV-03`, `F-GOV-04`)
- `CostControl` — hard and soft budget caps per user/agent/project/window
- `RateLimiter` — token-bucket, configurable scope and window
- `ModelPolicy` — per-role model allowlists (RBAC)

#### Audit (`F-OBS-02`, `F-OBS-03`)
- Hash-chain tamper detection — SHA-256 of previous record embedded in each entry
- Multi-agent DAG trace — `parent_trace_id` + `agent_id` form a directed acyclic graph
- `ElasticsearchSink` — production structured log storage

#### Guardrails (`F-QUA-01`, `F-QUA-02`)
- `GuardrailsInterceptor` with `OutputValidator` extension interface
- `JsonSchemaValidator` — validate structured LLM output against JSON Schema
- `RegexDenylistValidator` — pattern-based content filtering

#### Developer Experience (`F-DX-03`, `F-DX-04`, `F-DX-05`)
- `GavioTestKit` — unit-test interceptor chains in isolation (all 3 languages)
- OpenAI drop-in shim — point existing OpenAI SDK code at Gavio without changes
- JSON Schema config — `gateway.yaml` validated by published schema, IDE autocomplete

#### New providers
- `GeminiAdapter` — Gemini Pro, Flash, Ultra
- `AzureOpenAIAdapter` — Azure OpenAI deployment-based routing
- `OllamaAdapter` — local model support

### ⚠️ Breaking changes in v0.2.0
- `Gateway.call()` is deprecated in favour of `Gateway.complete()`. Removed in v0.4.0.
- `AuditRecord.version` field renamed to `AuditRecord.schema_version` for clarity.

---

## [0.3.0] — TBD _(planned)_

### Summary
Observability depth. Prompt lineage, LLM judge, hallucination detection,
OpenTelemetry, Prometheus metrics, and model version pinning.

### Planned additions
- `F-SEC-03` — PII Guard LLM tier (`LlmContextScanner`)
- `F-SEC-06` — Tool result sanitizer
- `F-REL-05` — Latency hedging
- `F-REL-08` — Model version pinning + change alerts
- `F-CACHE-05` — pgvector semantic backend
- `F-CACHE-06` — Cache invalidation API
- `F-OBS-04` — Prompt lineage (template + variables + RAG chunk sources)
- `F-OBS-07` — OpenTelemetry OTLP export
- `F-OBS-08` — Prometheus metrics (Micrometer for Java, prom-client for JS)
- `F-OBS-09` — PostgreSQL audit sink
- `F-QUA-03` — LLM judge validator
- `F-QUA-04` — Hallucination / faithfulness detection
- `F-QUA-06` — Risk scoring (composite: PII + guardrail + quality signals)
- `F-DX-06` — Config validation CLI (`gavio validate gateway.yaml`)
- `F-DX-07` — Scaffold CLI (`gavio init`)
- New providers: AWS Bedrock, Cohere

---

## [0.4.0] — TBD _(planned)_

### Summary
Compliance and oversight. EU AI Act + DORA export, human-in-the-loop gate,
data residency routing, multi-jurisdiction PII.

### Planned additions
- `F-SEC-07` — Data residency routing (GDPR Art. 44)
- `F-SEC-08` — Multi-jurisdiction PII rulesets (GDPR, HIPAA, PDPA, DPDP, LGPD, PIPL)
- `F-GOV-05` — A/B model routing with quality scoring
- `F-QUA-05` — Human-in-the-loop gate (pause + review queue)
- `F-QUA-07` — EU AI Act audit export (Article 12/13 signed bundle)
- `F-QUA-08` — DORA RTS audit export (Article 9 evidence package)
- New providers: HuggingFace Inference Endpoints, Vertex AI (direct)

### ⚠️ Breaking changes in v0.4.0
- `Gateway.call()` removed (deprecated since v0.2.0)

---

## [0.5.0] — TBD _(planned)_

### Summary
Advanced features. Multimodal, cost optimisation, right to erasure, dashboard.

### Planned additions
- `F-SEC-09` — Image PII detection (OCR + face detection on image inputs)
- `F-SEC-10` — Embedding call guard (PII pipeline on embedding API calls)
- `F-GOV-06` — Cost optimiser routing (auto-route to cheaper models)
- `F-GOV-07` — Drift detection (alert on response distribution changes)
- `F-OBS-10` — Agent call graph replay
- `F-QUA-09` — Right-to-erasure API (GDPR Art. 17 `purge(subject_id)`)
- `F-QUA-10` — License / copyright detection in responses
- `F-DX-08` — Read-only web dashboard (traces, costs, cache hit rate)

---

## [1.0.0] — TBD _(planned)_

### Summary
Stable release. API stability guarantee. Full documentation. LTS designation.

### Additions
- API stability guarantee: no breaking changes without a major version bump
- Full documentation at gavio.io (all guides, all API references)
- Performance benchmarks published (latency overhead per interceptor)
- Security audit completed and published
- Long-term support (LTS) designation — security patches for 24 months

### ⚠️ Breaking changes in v1.0.0
- Any remaining deprecated APIs removed
- Full list to be determined during v0.5.0 development

---

[Unreleased]: https://github.com/gavio-ai/gavio/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/gavio-ai/gavio/releases/tag/v0.1.0
