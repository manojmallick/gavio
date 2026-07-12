<div align="center">

# 🌉 Gavio

**The open standard AI gateway for production systems.**

PII protection · audit trails · reliability · cost control — as composable
interceptors. **Same API in Python, Java, and JavaScript.**

[![CI](https://github.com/manojmallick/gavio/actions/workflows/ci.yml/badge.svg)](https://github.com/manojmallick/gavio/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/gavio?color=3776ab&label=pypi&logo=python&logoColor=white)](https://pypi.org/project/gavio/)
[![npm](https://img.shields.io/npm/v/gavio?color=cb3837&label=npm&logo=npm)](https://www.npmjs.com/package/gavio)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.manojmallick/gavio-core?color=f89820&label=maven%20central&logo=apachemaven&logoColor=white)](https://central.sonatype.com/artifact/io.github.manojmallick/gavio-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-22c55e.svg)](LICENSE)
[![Zero deps](https://img.shields.io/badge/core%20dependencies-zero-22c55e)](#design-principles)
[![Docs](https://img.shields.io/badge/docs-live-7c6af7)](https://manojmallick.github.io/gavio/)

📖 **Docs:** [manojmallick.github.io/gavio](https://manojmallick.github.io/gavio/)

</div>

---

## What is Gavio?

Gavio sits **between your application and any LLM provider**. Every request
passes through a pre/post **interceptor chain** — PII redaction, retries, cost
tracking, audit logging — before and after the provider call:

```
Request → [ PII Guard · Secret Scanner · … ] → Provider → [ … · PII Restore · Audit ] → Response
```

Every team re-implements the same production concerns around LLM calls: redact
PII before it leaves the building, retry on 429s, fall back to a second
provider, log an audit trail, track spend. Gavio ships them once, as swappable
interceptors, with **identical behaviour across three languages** — enforced by
[shared test vectors](./test-vectors/).

- **Provider-agnostic** — OpenAI, Anthropic, Gemini, Azure, Ollama, Mock. Switching is a config change.
- **Zero mandatory dependencies** in every core (stdlib HTTP everywhere — no vendor SDKs).
- **Dev mode** — the whole stack runs in-process with a mock provider. No API key, no network.
- **Audit by default** — every call logged as metadata + SHA-256 content hashes (never raw text).
- **Inspector** — opt-in dev-time visualizer: live traces, per-interceptor waterfall, PII redaction diffs, and pipeline lints at `http://127.0.0.1:7411` (`inspect(true)` or `GAVIO_INSPECT=1`).
- **Inspector agentic & production mode** — multi-agent call graphs and session views, trace replay & edit-resend (full mode only), RED stats, hash-chain verification, PII-sanitized export of any trace as a test case, and a read-only dashboard over a persisted audit store: `gavio inspect --store audit.jsonl`.

> **Status:** v0.10.0 released; v0.11.0 Cost Intelligence is in progress.
> Semver stability holds since v0.2.0; pre-1.0, some APIs may still change.
> See the [CHANGELOG](./CHANGELOG.md).

---

## Architecture

Gavio is a thin core (`Gateway` + `InterceptorChain` + the request/response
model) that everything else plugs into. A request flows through a **pre**
pipeline, hits a **provider adapter**, then flows back through a **post**
pipeline in reverse order:

```
          ┌──────────────────────── Gateway.complete(request) ────────────────────────┐
          │                                                                            │
 request  │   PRE  ─▶ PiiGuard ─▶ SecretScanner ─▶ PromptInjectionGuard ─▶ RateLimiter │
 ───────▶ │           CostControl ─▶ CostRouter ─▶ SemanticCache ──┐                   │
          │                                                        │ (cache miss)      │
          │                                             ┌──────────▼──────────┐        │
          │                                             │  Provider Adapter    │        │
          │                                             │ OpenAI · Anthropic · │        │
          │                                             │ Gemini · Azure ·     │        │
          │                                             │ Ollama · Mock        │        │
          │                                             └──────────┬──────────┘        │
          │           Guardrails ◀─ RiskScorer ◀─ PiiRestore ◀─────┘                   │
 ◀─────── │   POST ◀─ Metrics ◀─ AuditInterceptor (hash-chained record)                │ response
          │                                                                            │
          └────────────────────────────────────────────────────────────────────────────┘
```

- **Interceptors** implement `before()` / `after()` / `onError()`. Order is
  explicit — PII redaction runs before audit; audit runs last so it records what
  every other interceptor did. See [docs/architecture.md](./docs/architecture.md).
- **Executor policies** (cache, retry, circuit breaker, load balancer, fallback)
  wrap the provider call itself — a cache hit or an open circuit short-circuits
  the provider entirely.
- **The audit record is metadata-only.** Prompts and responses are stored as
  SHA-256 hashes, never raw text; PII entity *types and counts* are logged, never
  values. Records are hash-chained (`F-OBS-02`) so any tampering is detectable.

**Core data model** — identical fields across all three SDKs, defined once in
[`spec/`](./spec/) as JSON Schema and enforced by [shared test vectors](./test-vectors/):

| `GavioRequest` | `GavioResponse` | `AuditRecord` |
|---|---|---|
| `trace_id` (UUID v7) | `trace_id` | `trace_id` · `parent_trace_id` |
| `agent_id` · `parent_trace_id` | `content` (PII restored) | `prompt_hash` · `response_hash` |
| `messages` · `model` · `provider` | `usage` · `cost_usd` · `latency_ms` | `pii_entity_types` · `risk_score` |
| `options` · `lineage` · `metadata` | `cache_hit` · `cache_type` | `previous_hash` · `lineage` · `schema_version` |

---

## Quick start (dev mode — no API key, no network)

<table>
<tr><th>Python</th><th>JavaScript / TypeScript</th><th>Java</th></tr>
<tr>
<td>

```python
from gavio import Gateway
from gavio.interceptors.pii import PiiGuard

gw = (Gateway.builder()
      .dev_mode(True)
      .use(PiiGuard())
      .build())

r = await gw.complete(messages=[
  {"role": "user",
   "content": "mail jan@example.com"}])
print(r.content)        # PII restored
print(r.audit.pii_entity_types)
```

</td>
<td>

```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const gw = new Gateway({ devMode: true })
  .use(piiGuard())

const r = await gw.complete({ messages: [
  { role: 'user',
    content: 'mail jan@example.com' }] })
console.log(r.content)   // PII restored
console.log(r.audit.piiEntityTypes)
```

</td>
<td>

```java
Gateway gw = Gateway.builder()
    .devMode(true)
    .use(new PiiGuard())
    .build();

var r = gw.complete(GavioRequest.builder()
    .message("user", "mail jan@example.com")
    .build()).join();
System.out.println(r.content());
System.out.println(r.audit().piiEntityTypes());
```

</td>
</tr>
</table>

All three print the reply with the email **restored**, and an audit record
showing `EMAIL` was detected and redacted before the (mock) provider ever saw it.

---

## Install

| Language | Command | Docs |
|---|---|---|
| **Python** 3.10+ | `pip install gavio` | [packages/gavio-py](./packages/gavio-py/README.md) · [docs/packages/python.md](./docs/packages/python.md) |
| **JavaScript** (Node 18+) | `npm install gavio` | [packages/gavio-js](./packages/gavio-js/README.md) · [docs/packages/javascript.md](./docs/packages/javascript.md) |
| **Java** 17+ (Maven) | `io.github.manojmallick:gavio-core:0.10.0` | [packages/gavio-java](./packages/gavio-java/README.md) · [docs/packages/java.md](./docs/packages/java.md) |

---

## Packages

Gavio is a monorepo. Each SDK is independently versioned-in-lockstep and
published to its native registry.

### 🐍 Python — `gavio` (PyPI)

The **reference implementation**. Async-first (`await gw.complete(...)`), sync
wrapper (`complete_sync`), full type hints + `py.typed`. Zero mandatory deps;
`gavio[redis]` adds a distributed cache backend, other optional extras
(`gavio[presidio]`, …) land in later versions.

```bash
pip install gavio
```

→ **[Full Python guide](./docs/packages/python.md)** · [package README](./packages/gavio-py/README.md)

### 🟨 JavaScript / TypeScript — `gavio` (npm)

Written in TypeScript, ships full type definitions, **dual ESM + CJS build**
with per-subpath `exports` for tree-shaking. Native `fetch`, `node:crypto`.
Node 18+, Deno, Bun.

```bash
npm install gavio
```

→ **[Full JavaScript guide](./docs/packages/javascript.md)** · [package README](./packages/gavio-js/README.md)

### ☕ Java — `io.github.manojmallick:gavio-*` (Maven Central)

Multi-artifact Maven project: `gavio-core` plus one artifact per interceptor
family (`gavio-interceptor-pii`, `-audit`, `-reliability`, `-cache`,
`-governance`, `-guardrails`, `-metrics`, `-quality`), one per provider
(`gavio-provider-openai`, `-anthropic`, `-gemini`, `-azure`, `-ollama`), and
`gavio-testing`. Immutable records + builders, `CompletableFuture` async,
Java 17+.

```xml
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-core</artifactId>
  <version>0.10.0</version>
</dependency>
```

→ **[Full Java guide](./docs/packages/java.md)** · [package README](./packages/gavio-java/README.md)

---

## What ships

Every feature below lands in **all three SDKs in lockstep**, at the same version,
gated by the same [shared test vectors](./test-vectors/).

### 🔒 Privacy & security

| Feature | ID | Since |
|---|---|---|
| PII Guard — Email, IBAN·mod-97, BSN·11-proef, CreditCard·Luhn, Phone, IP, SSN | `F-SEC-01` | 0.1.0 |
| Secret scanner — API keys, AWS `AKIA`, GitHub tokens, JWT, PEM, DB URLs | `F-SEC-04` | 0.1.0 |
| Prompt-injection defense — pattern corpus + optional semantic similarity | `F-SEC-05` | 0.2.0 |
| Embedding call guard — `gw.embed(texts)` runs the same PII pipeline before embedding APIs | `F-SEC-10` | 0.9.0 |

### 🔁 Reliability

| Feature | ID | Since |
|---|---|---|
| Retry (exp backoff + jitter), Fallback chain, Timeout | `F-REL-01/02/07` | 0.1.0 |
| Circuit breaker, Load balancer (weighted round-robin) | `F-REL-03/04` | 0.2.0 |
| Streaming reliability — buffer response before post-interceptors run | `F-REL-06` | 0.3.0 |

### 💰 Cost & governance

| Feature | ID | Since |
|---|---|---|
| Per-request `cost_usd` tracking (all providers) | `F-GOV-01` | 0.1.0 |
| Budget caps (soft/hard), rate limiting, model RBAC | `F-GOV-02/03/04` | 0.2.0 |
| **Cost-optimiser routing** — reroute simple prompts to a cheaper model | `F-GOV-06` | **0.5.0** |
| **Cost Intelligence** — tenant/feature/user attribution, cost reports, retry overhead, cache savings, scoped budget fallback | `F-COST-01/02/04` | **0.11.0** |

### ⚡ Caching

| Feature | ID | Since |
|---|---|---|
| Semantic + exact cache (cosine + SHA-256), in-memory backends | `F-CACHE-01/02/03` | 0.2.0 |
| Redis distributed backend (shared hits across processes, zero-dep RESP2) | `F-CACHE-04` | 0.4.0 |

### 📊 Observability & quality

| Feature | ID | Since |
|---|---|---|
| Audit interceptor + `AuditRecord` (SHA-256 hashes), stdout sink | `F-OBS-01/05` | 0.1.0 |
| Hash-chain (tamper-evident) audit, multi-agent DAG trace | `F-OBS-02/03` | 0.2.0 |
| Prompt lineage (template + variables + RAG sources) | `F-OBS-04` | 0.3.0 |
| Prometheus metrics (zero-dep text exposition) | `F-OBS-08` | 0.3.0 |
| Guardrails — JSON-schema + regex allow/deny | `F-QUA-01/02` | 0.2.0 |
| Composite risk scoring (PII + guardrail + injection signals) | `F-QUA-06` | 0.3.0 |
| JSONL audit sink (`jsonl://<path>`) — the store the production dashboard reads | `F-DX-08` | 0.7.0 |

### 🔬 Inspector

| Feature | ID | Since |
|---|---|---|
| Dev-time visualizer — live traces (SSE), waterfalls, PII diffs, pipeline lints, embedded UI | `F-DX-09/10` | 0.6.0 |
| Agent call graphs + session views (`/api/dag`, `/api/sessions`) | `F-OBS-10` | 0.7.0 |
| Trace replay & edit-resend (full capture mode only) | `F-DX-11` | 0.7.0 |
| Read-only production dashboard — RED stats, hash-chain verifier, `gavio inspect --store` | `F-DX-08` | 0.7.0 |
| Export any trace as a PII-sanitized `GavioTestKit` test / test vector | `F-DX-12` | 0.7.0 |
| Overhead benchmarks with CI-enforced budget (<1% metadata / <5% full p50) | `F-DX-09` | 0.8.0 |
| Cost reports — `/api/cost-report`, top spend dimensions, retry overhead, cache savings | `F-COST-02` | 0.11.0 |

### 🛠️ Developer experience & providers

| Feature | ID | Since |
|---|---|---|
| Dev mode, dry-run mode, `GavioTestKit` | `F-DX-01/02/03` | 0.1.0 |
| OpenAI drop-in shim, config loader | `F-DX-04/05` | 0.2.0 |
| **Providers** — OpenAI · Anthropic · Gemini · Azure OpenAI · Ollama · Mock (all stdlib HTTP, no vendor SDKs) | — | 0.1–0.2 |

Conformance-tested across all three SDKs on every push and PR
([`ci.yml`](./.github/workflows/ci.yml) runs Python 3.10–3.12, Node 18/20/22,
Java 17/21). Per-release test totals are in the [CHANGELOG](./CHANGELOG.md); see
the [interceptors guide](./docs/interceptors.md) for every built-in interceptor.

---

## Design principles

- **P1 · Interface-first** — every feature is a public interface you can swap or extend.
- **P2 · Interceptor chain** — pre/post hooks, explicit composition, no hidden magic.
- **P3 · Provider-agnostic** — no provider-specific code leaks into your app.
- **P4 · Zero infra in dev** — `dev_mode` runs everything in-process.
- **P5 · Audit by default** — opt-out, not opt-in.
- **P6 · Embeddable library** — runs in-process, no sidecar or proxy required.
- **P7 · Dry-run first** — log what *would* happen without blocking.
- **P8 · Typed everywhere** — TS generics, Python hints, Java generics.

---

## Documentation

| Doc | What |
|---|---|
| [docs/getting-started.md](./docs/getting-started.md) | 5-minute quickstart, all three languages |
| [docs/architecture.md](./docs/architecture.md) | Request lifecycle, the interceptor chain, data model |
| [docs/interceptors.md](./docs/interceptors.md) | Every built-in interceptor + writing your own |
| [docs/inspector.md](./docs/inspector.md) | The Inspector: dev visualizer, agent DAGs, replay, production dashboard |
| [docs/otel-mapping.md](./docs/otel-mapping.md) | InspectorEvent → OpenTelemetry spans · [Grafana dashboard](./docs/grafana/gavio-dashboard.json) |
| [docs/packages/](./docs/packages/) | Deep guide per SDK |
| [examples/](./examples/) | Runnable example projects in all three languages |
| [spec/](./spec/) | Canonical JSON Schema data model |
| [test-vectors/](./test-vectors/) | Shared cross-SDK conformance cases |
| [RELEASING.md](./RELEASING.md) | How releases are cut (PyPI + Maven Central + npm) |
| [CONTRIBUTING.md](./CONTRIBUTING.md) | Contribution guide + PR requirements |

---

## Repository layout

```
gavio/
├── spec/                     canonical data model (JSON Schema)
├── test-vectors/             shared cases every SDK must pass
├── packages/
│   ├── gavio-py/             Python SDK  (PyPI: gavio)
│   ├── gavio-js/             JS/TS SDK   (npm: gavio)
│   └── gavio-java/           Java SDK    (Maven: io.github.manojmallick:gavio-*)
├── docs/                     documentation
└── .github/workflows/        ci.yml (test all 3) · release.yml (publish all 3)
```

---

## License

[MIT](./LICENSE) © 2026 Manoj Mallick

---

<div align="center">

MIT © 2026 [Manoj Mallick](https://github.com/manojmallick) · Made in Amsterdam 🇳🇱

</div>
