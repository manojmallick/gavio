# Changelog

All notable changes to Gavio are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Feature IDs (e.g. `F-SEC-01`) group related changes across the three SDKs.

---

## [Unreleased]

### Added
- **Prompt lineage (all SDKs, `F-OBS-04`)** — new `PromptLineage` value type
  (`template_id`, `template_version`, `variables`, `rag_chunks`) plus a `RagChunk`
  source reference (`source`, `chunk_id`, `score`). Attach it to a
  `GavioRequest` (`lineage=`) and the `AuditInterceptor` copies it into the
  `AuditRecord` so any prompt can be reconstructed from its template, variable
  bindings, and RAG sources. RAG chunk **text is never stored** — only source
  references — keeping the audit record metadata-only. Lineage participates in
  the hash-chain `contentHash()`. Exported from each SDK's public API; documented
  in `spec/GavioRequest.schema.json` and `spec/AuditRecord.schema.json`. First
  feature of **v0.3.0 (Observability depth)**.
- **Streaming reliability (all SDKs, `F-REL-06`)** — `Gateway.stream(...)` runs a
  completion through the provider's streaming API but buffers the response in
  full via a new `StreamBuffer` before the post-interceptor pipeline (guardrails,
  PII restore, audit) runs — so every post-interceptor sees, and can rewrite or
  block, the complete response before any chunk reaches the caller. Adds
  `ProviderAdapter.build_stream_response()` (estimates usage from the buffered
  text) and a mock streaming implementation for dev mode. Executor policies
  (retry, circuit breaker, cache) are not applied to the streaming path in this
  release. First v0.2.0-planned reliability gap now closed. `StreamBuffer` lives
  in `io.gavio.providers` in Java (core cannot depend on the reliability module).
- **Prometheus metrics (all SDKs, `F-OBS-08`)** — `MetricsInterceptor` records
  per-request metrics into a `PrometheusMetrics` registry, exposed via
  `render()` as the Prometheus text exposition format: `gavio_requests_total`,
  `gavio_tokens_total{kind}`, `gavio_cost_usd_total`, `gavio_request_latency_ms`
  (histogram), and `gavio_cache_hits_total` — all labelled by `provider` and
  `model`. Hand-rolled exposition, **zero runtime dependencies**. Java ships a
  new `gavio-interceptor-metrics` module; JS adds the `gavio/interceptors/metrics`
  subpath. Another v0.3.0 (Observability depth) feature.
- **Risk scoring (all SDKs, `F-QUA-06`)** — `RiskScorer` folds the per-request
  signals other interceptors leave on the context — PII entities found, guardrail
  outcome (`FAIL`/`HITL`), and the prompt-injection risk — into a single
  composite score in `[0, 1]`, written to `ctx.risk_score` and recorded on the
  `AuditRecord`. Configurable weights (default PII 0.3 · guardrail 0.4 · injection
  0.3), clamped; exposes a pure `score(...)` method. Introduces a new `quality`
  interceptor family (`gavio.interceptors.quality`, `gavio/interceptors/quality`,
  Java module `gavio-interceptor-quality`) for the F-QUA features. Another v0.3.0
  feature.

### Fixed
- **JS audit hash chain** — `AuditRecord.toCanonicalJson()` now sorts keys
  recursively so nested fields (`tokenUsage`, `lineage`) actually contribute to
  the chain hash; the previous array-replacer dropped all nested content.

---

## [0.2.0] — 2026-07-01

### Summary
**Production core.** Semantic + exact caching, hash-chain audit, multi-agent DAG
trace, circuit breaker, load balancing, budget/rate-limit/RBAC governance,
guardrails (JSON-schema + regex), prompt-injection defense, and Gemini / Azure
OpenAI / Ollama providers — shipped across **Python, Java, and JavaScript**.

Tests: Python 102 · JavaScript 115 · Java 108. Feature IDs `F-CACHE-01/02/03`,
`F-OBS-02/03`, `F-REL-03/04`, `F-GOV-02/03/04`, `F-QUA-01/02`, `F-SEC-05`,
`F-DX-04/05`. The entries below were implemented first in the Python reference
and ported to JS and Java in lockstep (Java's standalone JSON config loader is
deferred — the Spring starter / explicit builders are the idiomatic path).

### Added
- **Caching** — `SemanticCache` interceptor:
  - `F-CACHE-01` exact SHA-256 cache (keyed on provider + model + messages +
    options); a hit returns the cached response and skips the provider.
  - `F-CACHE-02` semantic cache — cosine similarity over embeddings, with a
    configurable `similarity_threshold`. Ships a zero-dependency
    `HashingEmbedder` (pluggable `Embedder` protocol for real embedders).
  - `F-CACHE-03` in-memory backends — `MemoryBackend` (exact) and
    `InMemoryVectorBackend` (semantic).
  - Implemented as an `ExecutorPolicy` (register outermost); sets `cache_hit` /
    `cache_type` on the response. Composes with `PiiGuard` — a cache hit still
    restores PII in the response.
- **Hash-chain audit (all SDKs, `F-OBS-02`)** — `AuditInterceptor(hash_chain=True)`
  links each record via `previous_hash` (SHA-256 of the prior record);
  `verify_chain(records)` detects any edit, reorder, or deletion.
- **Multi-agent DAG trace (all SDKs, `F-OBS-03`)** — `build_call_graph(records)`
  reconstructs the call graph from `parent_trace_id` + `agent_id`.
- **Circuit breaker (all SDKs, `F-REL-03`)** — `CircuitBreaker` `ExecutorPolicy`
  with closed/open/half-open states; fast-fails with `CircuitOpenError` while
  open, probes on recovery.
- **Governance (all SDKs)** — `CostControl` budget caps (`F-GOV-02`, soft/hard per
  scope+window), `RateLimiter` (`F-GOV-03`, requests + tokens per minute),
  `ModelPolicy` RBAC allowlists (`F-GOV-04`). New errors: `RateLimitExceededError`,
  `ModelNotAllowedError`.
- **Guardrails (all SDKs)** — `GuardrailsInterceptor` (`ExecutorPolicy`, on_failure
  error/retry/warn) with `JsonSchemaValidator` (`F-QUA-01`, zero-dep JSON Schema
  subset) and `RegexDenylistValidator` / `RegexAllowlistValidator` (`F-QUA-02`);
  records `guardrail_outcome` for the audit trail.
- **New provider adapters (all SDKs)** — `GeminiAdapter` (role/system mapping),
  `AzureOpenAIAdapter` (deployment routing), `OllamaAdapter` (local, free);
  registered in the provider registry with pricing entries. All stdlib HTTP.
- **Prompt injection defense (all SDKs, `F-SEC-05`)** — `PromptInjectionGuard`
  (curated pattern corpus + optional semantic similarity); block or flag,
  records a `risk_score`. New `PromptInjectionError`.
- **Load balancing (all SDKs, `F-REL-04`)** — `LoadBalancer` `ExecutorPolicy`,
  weighted round-robin across a pool of provider adapters.
- **OpenAI drop-in shim (all SDKs, `F-DX-04`)** — `gavio.shim.openai.GavioOpenAI`
  with an OpenAI-client-shaped `chat.completions.create` / `acreate`.
- **Config loader (Python + JavaScript; Java deferred, `F-DX-05`)** — `Gateway.from_config(path | dict)`
  builds a gateway from JSON (stdlib) or YAML (optional PyYAML), with `${ENV}`
  expansion.

### Changed
- `ExecutorPolicy` moved to `gavio.interceptors.executor` (re-exported from
  `interceptors.reliability.policy` for compatibility) so caching and
  reliability share it.

---

## [0.1.0] — 2026-07-01

### Summary
Foundation release. Working interceptor pipeline with provider adapters,
PII Guard (regex tier), audit logging, retry/fallback, and dev mode.
Ships across Python, Java, and JavaScript simultaneously — plus a canonical
spec (`spec/`) and shared cross-SDK test vectors (`test-vectors/`).

**224 tests total** — Python 63, JavaScript 85, Java 76, each including the
shared vectors. CI (`.github/workflows/ci.yml`) runs all three suites on every
push and PR. Provider adapters use no vendor SDKs (stdlib HTTP everywhere).
The JavaScript package ships a dual ESM + CJS build with per-subpath type
definitions. Monotonic UUID v7 trace-id format verified identical across SDKs.

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
- All artifacts published to Maven Central under `io.github.manojmallick` groupId

#### JavaScript-specific
- `gavio` package on npm — ESM + CJS dual build
- Full TypeScript 5.0+ type definitions included
- Zero mandatory dependencies
- Sub-path imports for tree-shaking (`gavio/interceptors/pii`, etc.)
- Node.js 18+, Deno 1.40+, Bun 1.0+ support
- `edgeMode: true` for Cloudflare Workers / Vercel Edge

#### Cross-SDK infrastructure
- Canonical spec in `spec/` — JSON Schema (Draft 2020-12) for `GavioRequest`,
  `GavioResponse`, `AuditRecord`, `PiiMatch`, `InterceptorResult`
- Shared test vectors in `test-vectors/pii/` (`checksums.json`, `detection.json`)
  loaded and run by all three SDKs, enforcing behavioural parity
- CI workflow (`.github/workflows/ci.yml`) running Python (3.10–3.12),
  JavaScript (Node 18/20/22), and Java (17/21) suites incl. the shared vectors
- Repository governance: `LICENSE` (MIT), `CONTRIBUTING.md`, `SECURITY.md`,
  `CODE_OF_CONDUCT.md`

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

[Unreleased]: https://github.com/manojmallick/gavio/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/manojmallick/gavio/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/manojmallick/gavio/releases/tag/v0.1.0
