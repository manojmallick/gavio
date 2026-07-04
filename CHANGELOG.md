# Changelog

All notable changes to Gavio are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Feature IDs (e.g. `F-SEC-01`) group related changes across the three SDKs.

---

## [Unreleased]

Nothing yet.

---

## [0.10.0] — 2026-07-04

### Summary
**Compliance & governance depth.** The four v0.5.0 carried-forward features land
across **Python, Java, and JavaScript** — license/copyright detection
(`F-QUA-10`), GDPR Art. 17 right-to-erasure (`F-QUA-09`), drift detection
(`F-GOV-07`), and image PII detection (`F-SEC-09`) — plus the first
**domain-aware policy pack** (FinTech identifiers). Feature PRs #40, #41, #43,
#45, #47.

Tests: Python 229 · JavaScript 243 · Java modules green.

### Added
- **License / copyright detection (all SDKs, `F-QUA-10`)** — new `licenseDetector`
  output validator flags known open-source license text (MIT, Apache-2.0,
  GPL-2.0/3.0, BSD-3-Clause, MPL-2.0) in model responses before it lands in user
  code. Matches a shipped corpus of hashed 8-word shingles — hashes only, no
  license text is bundled — and drops shingles shared by more than one license so
  a hit is discriminative (GPL-2.0 vs GPL-3.0 don't cross-fire). Detections
  surface in the guardrail outcome and audit record. Shared cases in
  `test-vectors/license/detection.json`. PR #40 (#33).
- **Right-to-erasure API — GDPR Art. 17 (all SDKs, `F-QUA-09`)** — a `subject_id`
  passed in request metadata is now persisted on every `AuditRecord` (new field,
  added to `spec/AuditRecord.schema.json`). Audit sinks gain
  `purge(subject_id) -> count`: a no-op for non-persistent sinks (stdout) and a
  real implementation on the built-in `JsonlSink`, which is now shipped in all
  three SDKs so erasure is testable everywhere. Scope and limits documented in
  `docs/interceptors.md`. PR #41 (#32).
- **Drift detection (all SDKs, `F-GOV-07`)** — new `DriftMonitor` interceptor
  with a pluggable `DriftDetector` interface and a default
  `StatisticalDriftDetector` (rolling-window baseline per metric, z-score
  threshold). Watches response-distribution signals (latency, tokens, cost,
  risk) and, when one drifts, emits a standalone `governance.event` inspector
  event — the reserved type is now wired in `spec/InspectorEvent.schema.json` —
  and counts it in `driftAlerts` on `/api/stats`. Observe-only. PR #43 (#31).
- **Image PII detection (all SDKs, `F-SEC-09`)** — new `ModalityScanner`
  interface + `ModalityGuard` interceptor extend the PII pipeline to image
  inputs passed on a side-channel `images` request field (added to
  `spec/GavioRequest.schema.json`). Each image's OCR text runs through the
  tier-1 text scanners and direct detections (e.g. `FACE`) are unioned in;
  detections land in the `AuditRecord`'s `pii_entity_types` like text PII, with
  `onDetect='block'` optional. Ships a reference `OcrModalityScanner` behind an
  optional dependency (Python `[ocr]` extra, JS `tesseract.js` peer dep, Java
  tess4j via reflection). Shared cases in `test-vectors/pii/image-detection.json`.
  PR #45 (#29).
- **FinTech domain policy pack (all SDKs)** — first domain-aware policy pack:
  a context-gated SWIFT/BIC scanner (`SWIFT_BIC`) and a US ABA routing-number
  scanner (`ROUTING_NUMBER`, mod-10 checksum), exposed as a
  `fintechScanners()` / `fintech_scanners()` / `DefaultScanners.fintech()`
  factory composed with the defaults. Shared cases in
  `test-vectors/pii/fintech-detection.json`. PR #47.

This completes the four v0.5.0 carried-forward features (F-QUA-10, F-QUA-09,
F-GOV-07, F-SEC-09) and adds the first domain policy pack; what remains for
v1.0.0 is the release gate (API-stability guarantee, docs site, published
benchmarks, security audit, LTS).

---

## [0.9.0] — 2026-07-04

### Summary
**Embedding call guard.** Embedding API calls no longer bypass the PII
pipeline: `Gateway.embed(texts)` runs every input through the same
interceptor chain as completions in **Python, Java, and JavaScript**
(`F-SEC-10`, carried forward from the v0.5.0 milestone). Plus a full refresh
of every user-facing doc surface. Feature PR #37, docs PR #38.

Tests: Python 175 · JavaScript 188 · Java 16 modules green.

### Added
- **Embedding call guard (all SDKs, `F-SEC-10`)** — new `Gateway.embed(texts)`
  entry point runs embedding inputs through the same interceptor pipeline as
  completions: PII guard scans/redacts every text before the provider's
  embedding API is called, governance and audit interceptors fire, and the
  Inspector traces the call. `GavioResponse` gains an `embeddings` field (one
  vector per input); provider adapters gain an optional `embed()` (MockProvider
  implements a deterministic reference); adapters without embedding support
  fail with a clear `ProviderError`. Shared redaction cases in
  `test-vectors/embedding/redaction.json` (#30).

### Changed
- Documentation refresh (#38): package READMEs rewritten to the current
  feature set (they described v0.1.0), Inspector + Embeddings sections added
  to all six per-SDK guides, main-README feature tables corrected, examples
  re-verified against the published packages, and new `05-inspector`
  multi-agent examples (Python + JS).

---

## [0.8.0] — 2026-07-04

### Summary
**Inspector performance budget, enforced.** The `benchmarks/inspector/`
overhead suite promised by the Inspector plan (deferred since v0.6.0) now
runs in CI for all three SDKs, and the registry project links (PyPI /
Maven Central) point at the real repository. Feature PR #35.

Measured overhead vs the plan's budgets (<1% metadata, <5% full):
metadata 0.13–0.58% p50, full 0–2.7% across the three SDKs.

Tests: Python 168 · JavaScript 181 · Java 16 modules green.

### Added
- **Inspector overhead benchmarks (`F-DX-09`)** — `benchmarks/inspector/`
  harnesses in all three SDKs measure per-request latency with the inspector
  disabled / metadata / full against a delay-padded mock provider, enforcing
  the INSPECTOR_PLAN §13 performance budget with CI thresholds (metadata p50
  overhead < 10% of the simulated call, full < 25%); new `benchmarks` CI job
  runs all three on every PR (#34).

### Fixed
- Registry project links: `pyproject.toml` Homepage/Repository/Changelog
  pointed at the unpurchased `gavio.io` domain and a nonexistent `gavio-ai`
  org (rendered broken on PyPI); now `manojmallick.github.io/gavio` and
  `github.com/manojmallick/gavio` (+ new Issues link). Maven parent pom
  `<url>` likewise (#35).

---

## [0.7.0] — 2026-07-04

### Summary
**Inspector: agentic & production mode.** The v0.7.0 milestone completes the
Inspector across **Python, Java, and JavaScript**: multi-agent call graphs and
session views, trace replay & edit-resend, RED stats, hash-chain verification,
PII-sanitized test-case export, a cost simulator, and the read-only
production dashboard over a persisted audit store (`gavio inspect --store`).
Feature IDs `F-OBS-10`, `F-DX-11`, `F-DX-08`, `F-DX-12`. Feature PR #27,
plus repo hygiene in #25.

Tests: Python 168 · JavaScript 181 · Java 16 modules green.

### Added
- **Agent call graph & session views (all SDKs, `F-OBS-10`)** — `GET /api/dag`
  (`?root=<trace_id>` or `?session_id=`) builds the multi-agent call graph from
  `parent_trace_id`/`agent_id` links with per-node and subtree
  cost/latency/status rollups; `GET /api/sessions` lists sessions with trace
  counts, errors, agents, total cost and duration. New DAG and Sessions tabs in
  the bundled Inspector UI. Shared DAG-assembly cases in
  `test-vectors/inspector/api-cases.json`.
- **Trace replay & edit-resend (all SDKs, `F-DX-11`)** — `POST /api/replay`
  re-fires a captured request through the live gateway (full interceptor chain,
  never bypassed), optionally with edited `messages`/`model`/`options`;
  returns the new `trace_id`. Available only in `full` capture mode — 403
  otherwise (gating cases shared in `test-vectors/inspector/api-cases.json`).
  Replay / Edit & resend actions in the UI trace detail.
- **Read-only dashboard over the audit store (`F-DX-08`)** — new `gavio`
  CLI (Python): `gavio inspect --store audit.jsonl` serves the Inspector in
  `metadata` mode from a persisted audit store with no running gateway. New
  `JsonlSink` audit sink (`jsonl://<path>`) writes the store. All SDKs gain
  `GET /api/stats` (RED aggregates — rate, error %, latency p50/p95/p99,
  tokens, cost, cache hit-rate, PII detections by entity type; `group_by`
  provider/model/agent_id, `since` filter) and a Stats tab in the UI;
  `GET /api/chain/verify` walks the `previous_hash` links (`F-OBS-02`
  surfaced) and reports the first broken record; `/api/traces?q=` looks up
  traces by trace-id or content-hash prefix.
- **Export trace as test case (all SDKs, `F-DX-12`)** —
  `GET /api/traces/{id}/export?format=test-vector|testkit-py|testkit-java|testkit-js`
  renders a captured trace as a shared `test-vectors/` JSON case or a runnable
  `GavioTestKit` unit test; detected PII values are replaced with synthetic
  fixtures before export. 403 in `metadata` mode.
- **Cost simulator (all SDKs)** — `GET /api/simulate-cost?trace_id=&model=`
  recosts a trace's token usage under a different model via `PricingProvider`.
  Trace summaries now carry token usage from `provider.call.end`.
- **Fleet observability extras** — prebuilt Grafana dashboard over the
  `F-OBS-08` Prometheus metrics (`docs/grafana/gavio-dashboard.json`) and the
  canonical InspectorEvent → OpenTelemetry span mapping (`docs/otel-mapping.md`,
  `F-OBS-07` groundwork).

### Changed
- Repo hygiene: harness-generated files (`.github/copilot-instructions.md`,
  `.claude/settings.json`) are untracked and gitignored (#25).

---

## [0.6.0] — 2026-07-03

### Summary
**Inspector: dev-time visualizer.** The v0.6.0 milestone — an embedded,
zero-dependency inspector across **Python, Java, and JavaScript**: live span
events for every request through the chain, a bounded ring buffer, a localhost
JSON API with SSE, and a self-contained web UI. Feature IDs `F-DX-09` and
`F-DX-10`. Streaming emits a reduced event set (the buffered path) and
`benchmarks/inspector/` is deferred to a follow-up.

Tests: Python 148 · JavaScript 162 · Java 157.

### Added
- **Inspector core (all SDKs, `F-DX-09`)** — embedded, zero-dependency dev-time
  visualizer plumbing: an in-process `InspectorBus` emits span events
  (`trace.start`, per-interceptor `before`/`after` start+end with duration,
  mutation flag and decision records, `provider.call.*`, `trace.error`,
  `trace.end`) while a request moves through the chain; a bounded ring buffer
  assembles them into traces; an embedded localhost HTTP server (stdlib only)
  serves a JSON API — `/api/health`, `/api/pipeline` (chain composition +
  ordering lints), `/api/traces`, `/api/traces/{id}`, `/api/stream` (SSE).
  Canonical event contract in `spec/InspectorEvent.schema.json`; shared
  `test-vectors/inspector/event-sequences.json` cases run in all three suites.
- **Inspector UI (all SDKs, `F-DX-10`)** — single self-contained HTML file
  (source: `inspector-ui/index.html`, vendored into each package) served at
  `/`: live trace list over SSE, per-trace waterfall with span timings,
  mutation diff / PII redaction pane, decision records, pipeline view with
  ordering lints. Works fully offline; no external resources.
- **Capture modes** — `full` / `redacted` / `metadata` with *structural*
  content gating: `metadata` events carry no message/response content by
  construction; `full` refuses to start outside dev mode without an explicit
  acknowledgement; secret values are masked in every mode. Inspector is off
  by default; enable via the gateway builder (`inspect(...)`) or
  `GAVIO_INSPECT=1` (`GAVIO_INSPECT_PORT`, `GAVIO_INSPECT_MODE`). Server binds
  `127.0.0.1` by default; non-loopback binds require an auth token.
- **`InterceptorContext.inspect(key, value)`** — custom interceptors can attach
  decision records to their span events; interceptor state entries keyed by
  interceptor name (e.g. `cost_router`) surface automatically.

---

## [0.5.1] — 2026-07-03

### Summary
**Patch release.** Documentation corrections and expanded `F-GOV-06` test
coverage — no runtime code changes; the published artifacts are functionally
equivalent to v0.5.0.

### Added
- **Cost-optimiser routing test coverage (`F-GOV-06`)** — each SDK's governance
  suite now asserts the decision record `CostRouter` writes to
  `ctx.state["cost_router"]` (`rerouted`, `original_model`, `complexity_score`)
  and covers the threshold boundary (a score exactly at `complexity_threshold`
  does not reroute).

### Fixed
- **Docs** — corrected stale version pins missed by the v0.5.0 cut: the Java
  package README (`0.1.0` → `0.5.0`), the `examples/java` Maven poms
  (`0.4.0` → `0.5.0`), and `SECURITY.md` supported versions (`0.1.x` → `0.5.x`).
  Refreshed the root README (architecture section, feature list through v0.5.0)
  and completed the changelog version-comparison footer links.

---

## [0.5.0] — 2026-07-03

### Summary
**Cost-optimiser routing.** The first slice of the roadmap's "Advanced
features" milestone — `CostRouter` across **Python, Java, and JavaScript**.
Note this is a partial release against that theme: `F-SEC-09/10`, `F-GOV-07`,
`F-OBS-10`, `F-QUA-09/10`, and `F-DX-08` remain unstarted. Feature ID
`F-GOV-06`.

Tests: Python 133 · JavaScript 146 · Java 140.

### Added
- **Cost-optimiser routing (all SDKs, `F-GOV-06`)** — `CostRouter` reroutes a
  request to a cheaper `simple_model` when a pluggable `ComplexityScorer`
  scores its prompt below `complexity_threshold`. Ships a zero-dependency
  default `HeuristicComplexityScorer` (prompt length via the same token
  estimator `PricingProvider` uses, plus reasoning-keyword density). Java's
  `GavioRequest` gained `withModel(String)` alongside its existing
  `withProvider`, so the rewrite mirrors the established
  `withMessages`/`withProvider`/`copy_with_messages` idiom used by
  `LoadBalancer`/`FallbackChain`. Records its decision in
  `ctx.state["cost_router"]` — no `AuditRecord` schema changes needed, since
  the audit trail's `model` field already reflects the rerouted model.

---

## [0.4.0] — 2026-07-02

### Summary
**Distributed caching.** A production-grade Redis backend for `SemanticCache`
— shared exact and semantic cache hits across processes — across **Python,
Java, and JavaScript**. Deferred from v0.2.0, closes out the last of the three
issues that carried over from that release. Feature ID `F-CACHE-04`.

### Added
- **Redis cache backend (all SDKs, `F-CACHE-04`)** — `RedisBackend`/`RedisVectorBackend`
  (Python), `redisCacheBackend`/`redisVectorBackend` (JavaScript), and
  `RedisCacheBackend`/`RedisVectorBackend` (Java) give `SemanticCache` a
  production-grade, distributed cache backend so exact and semantic hits are
  shared across processes. Python uses the optional `redis` package
  (`pip install gavio[redis]`); JavaScript and Java hand-roll a minimal RESP2
  client over `node:net`/`java.net.Socket` — zero runtime dependencies, matching
  the project's stdlib-only provider adapters. Entries are namespaced under a
  Redis Set index so `clear()` only removes keys the backend itself wrote,
  never the whole database; TTLs use Redis's native expiry. The in-memory
  backends remain the zero-infra default (design principle P4). Wired into the
  Python and JavaScript config loaders (`semantic_cache.backend: redis`).
  Deferred from v0.2.0.

---

## [0.3.0] — 2026-07-01

### Summary
**Observability depth.** Prompt lineage, Prometheus metrics, and composite risk
scoring — plus buffered streaming reliability — across **Python, Java, and
JavaScript**. Two new interceptor families ship: `metrics` (Prometheus) and
`quality` (risk scoring). Semver stability continues.

Tests: Python 122 · JavaScript 135 · Java (16 Maven modules). Feature IDs
`F-OBS-04`, `F-OBS-08`, `F-QUA-06`, `F-REL-06`.

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

[Unreleased]: https://github.com/manojmallick/gavio/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/manojmallick/gavio/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/manojmallick/gavio/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/manojmallick/gavio/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/manojmallick/gavio/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/manojmallick/gavio/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/manojmallick/gavio/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/manojmallick/gavio/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/manojmallick/gavio/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/manojmallick/gavio/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/manojmallick/gavio/releases/tag/v0.1.0
