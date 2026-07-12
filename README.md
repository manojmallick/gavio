<div align="center">

# Gavio

**The AI request runtime and inspector for production systems.**

PII protection · audit trails · runtime events · reliability · cost control — as composable
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
tracking, audit logging, policy packs, tool runtime, runtime context — before and after the
provider call:

```
Request → [ PII Guard · Secret Scanner · … ] → Provider → [ … · PII Restore · Audit ] → Response
```

Every team re-implements the same production concerns around LLM calls: redact
PII before it leaves the building, retry on 429s, fall back to a second
provider, log an audit trail, track spend. Gavio ships them once, as swappable
interceptors, with **identical behaviour across three languages** — enforced by
[shared test vectors](./test-vectors/).

- **Provider-agnostic** — OpenAI, Anthropic, Gemini, Azure, OpenRouter, Ollama, Mock. Switching is a config change.
- **Zero mandatory dependencies** in every core (stdlib HTTP everywhere — no vendor SDKs).
- **Dev mode** — the whole stack runs in-process with a mock provider. No API key, no network.
- **Audit by default** — every call logged as metadata + SHA-256 content hashes (never raw text).
- **Runtime event export** — metadata-safe JSONL events for integrations with gateways, observability, and eval workflows.
- **Ecosystem integrations** — compatibility matrix, dependency-light metadata helpers, and adapter payloads for LiteLLM, promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, Vercel AI SDK, and related stack tools.
- **Self-hosted Control Plane** — optional local/private server for runtime config, policy rollout, budget config, audit search, and cached SDK fallback.
- **Production Trust Package** — metadata-only release evidence bundles that verify audit-chain, runtime-event, policy, benchmark, and document evidence.
- **Platform Runtime Profile** — metadata-only readiness score and gap report for platform-grade production runtime posture.
- **OTel bridge** — runtime events map to OpenTelemetry-style spans for production APM pipelines.
- **Inspector** — opt-in dev-time visualizer: live traces, per-interceptor waterfall, PII redaction diffs, and pipeline lints at `http://127.0.0.1:7411` (`inspect(true)` or `GAVIO_INSPECT=1`).
- **Inspector agentic & production mode** — multi-agent call graphs and session views, trace replay & edit-resend (full mode only), RED stats, hash-chain verification, PII-sanitized export of any trace as a test case, and a read-only dashboard over a persisted audit store: `gavio inspect --store audit.jsonl`.

> **Status:** v2.4.0 is the current stable package line. Gavio has an API
> stability guarantee, a 24-month 1.x LTS policy, and release automation that
> checks lockstep SDK versions before publishing. See
> [STABILITY.md](./STABILITY.md) and the [CHANGELOG](./CHANGELOG.md).

---

## Version map

The package line stays normal semver: v0.11.0 through v0.14.0 completed the
last pre-1.0 product milestones, then v1.0.0 became the stable release.

| Version | Focus | Main shipped surface |
|---|---|---|
| `0.11.0` | Cost Intelligence | Tenant/feature/user attribution, cost reports, retry overhead, cache savings, scoped budget fallback |
| `0.12.0` | Policy Pack architecture | Core and FinTech policy-pack manifests plus custom regex-rule packs |
| `0.13.0` | Adapter & positioning | OpenRouter adapter, first-class runtime context, AI Request Runtime / Inspector positioning |
| `0.14.0` | Tool Runtime | Tool schema validation, freshness checks, conflict detection, confidence, provenance |
| `1.0.0` | Stable release gate | Lockstep SDK versions, API stability, 24-month 1.x LTS policy, release hygiene checks |
| `1.1.0` | Positioning + Integration Foundation | Metadata-safe runtime event/export contract, JSONL exporters, integration docs, runtime export examples |
| `1.2.0` | Cost Governance v2 | Budget policy/decision contracts, budget stores, fallback/downgrade/dry-run decisions, budget-aware reports, CLI report |
| `1.3.0` | Observability + OTel | OpenTelemetry-style span exporter, shared OTel vectors, JSONL-to-OTel conversion |
| `1.4.0` | Prompt Registry + Evals | Versioned prompt templates, metadata-only lineage, deterministic eval suites and privacy-safe reports |
| `1.5.0` | Tool Runtime v2 | Registry-backed permissions, approval gates, replay records, MCP metadata capture |
| `1.6.0` | Policy Pack Catalog | Signed domain policy-pack manifests, catalog loaders, overrides, suppression rules, and domain examples |
| `1.7.0` | Self-hosted Control Plane | Local/private runtime config, policy rollout, budget config, audit search, snapshots, and SDK cache fallback |
| `1.8.0` | Production Trust Package | Metadata-only release evidence bundles, deterministic verification, threat model, benchmark and reference-architecture docs |
| `1.9.0` | Ecosystem + Integrations | Integration catalog helpers, shared compatibility vector/schema, common AI stack recipes, and full-stack integration smoke example |
| `2.0.0` | Platform-Grade Runtime | Metadata-only platform runtime profile, readiness score, deterministic gap checks, and cross-SDK posture helpers |
| `2.1.0` | Eval Runner + CI Gates | `gavio eval run` for JSON/YAML suites, baseline comparison, fail-under gates, and JSON/JUnit reports |
| `2.2.0` | Prompt Registry v2 | File-backed prompt manifests, semver selectors, approval metadata, metadata-safe diffs, and HMAC signatures |
| `2.3.0` | Control Plane Persistence | SQLite migrations, Postgres adapter path, durable projects/envs/keys/policies/budgets/events/audit search |
| `2.4.0` | Eval + Prompt Workflow | Prompt-to-eval links, per-version regression gates, failure triage metadata, and prompt release bundles |
| `2.5.0` | Ecosystem Adapters | Metadata-only adapter payload helpers for LiteLLM, promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and Vercel AI SDK |

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
          │                                             │ OpenRouter · Ollama ·│        │
          │                                             │ Mock                 │        │
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

## Runtime Intelligence

- **AI Request Inspector** — opt-in live traces, per-interceptor waterfall, replay, agent DAGs, RED stats, and production audit-store views.
- **Runtime Event Export** — metadata-safe JSONL and OpenTelemetry-style span streams for gateways, observability systems, and eval workflows.
- **Ecosystem Integrations** — compatibility matrix, metadata helpers, adapter payload helpers, and offline recipes for common gateways, observability tools, eval tools, frameworks, and provider SDKs.
- **Prompt Registry + Evals** — file-backed semver prompt manifests, approval metadata, metadata-safe diffs, prompt lineage, deterministic eval reports, `gavio eval run` CI gates, prompt-to-eval links, triage metadata, and prompt release bundles.
- **Cost Intelligence** — tenant/feature/user attribution, `/api/cost-report`, retry overhead, cache savings, and scoped budget fallback.
- **Cost Governance v2** — budget policy/decision contracts, projected-spend controls, fallback/downgrade/dry-run actions, budget-aware reports, and `gavio cost report`.
- **Domain-aware Policy Packs** — signed catalog manifests for core, finance, healthcare, legal, HR, support, code security, and regional identifiers, plus custom regex-rule packs with overrides and false-positive suppression.
- **Tool Runtime** — validate tool inputs/outputs, freshness, conflicts, permissions, approvals, replay records, and MCP provenance before tool results re-enter model context.
- **Runtime context** — interceptors can now read first-class `tenant`, `feature`, `cost`, `retry`, `tools`, and `policy` fields derived from request metadata.
- **Self-hosted Control Plane** — runtime projects, environments, hashed keys, policy rollout, budget config, audit/event search, config snapshots, and v2.3.0 durable SQLite/Postgres storage for local/private deployments.
- **Production Trust Package** — build and verify metadata-only evidence bundles for release reviews, threat models, benchmarks, and production architecture signoff.
- **Platform Runtime Profile** — compute metadata-only readiness scores and deterministic gaps across audit, runtime events, policies, costs, tools, and trust evidence.

---

## Quick start (dev mode — no API key, no network)

<table>
<tr><th>Python</th><th>JavaScript / TypeScript</th><th>Java</th></tr>
<tr>
<td>

```python
import asyncio

from gavio import Gateway
from gavio.interceptors.pii import PiiGuard

async def main():
    gw = (Gateway.builder()
          .dev_mode(True)
          .use(PiiGuard())
          .build())

    r = await gw.complete(messages=[
      {"role": "user",
       "content": "mail jan@example.com"}])
    print(r.content)        # PII restored
    print(r.audit.pii_entity_types)

asyncio.run(main())
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
import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.GavioResponse;
import io.gavio.interceptors.audit.AuditRecord;
import io.gavio.interceptors.pii.PiiGuard;

Gateway gw = Gateway.builder()
    .devMode(true)
    .use(new PiiGuard())
    .build();

GavioResponse r = gw.complete(GavioRequest.builder()
    .message("user", "mail jan@example.com")
    .build()).join();

AuditRecord audit = (AuditRecord) r.audit();
System.out.println(r.content());
System.out.println(audit.piiEntityTypes());
```

</td>
</tr>
</table>

All three print the reply with the email **restored**, and an audit record
showing `EMAIL` was detected and redacted before the (mock) provider ever saw it.
The Java snippet uses `gavio-core`, `gavio-interceptor-pii`, and
`gavio-interceptor-audit`; the complete runnable project is
[examples/java/01-quickstart](./examples/java/01-quickstart/).

---

## Install

| Language | Command | Docs |
|---|---|---|
| **Python** 3.10+ | `pip install gavio==2.4.0` | [packages/gavio-py](./packages/gavio-py/README.md) · [docs/packages/python.md](./docs/packages/python.md) |
| **JavaScript** (Node 18+) | `npm install gavio@2.4.0` | [packages/gavio-js](./packages/gavio-js/README.md) · [docs/packages/javascript.md](./docs/packages/javascript.md) |
| **Java** 17+ (Maven) | `io.github.manojmallick:gavio-core:2.4.0` plus interceptor artifacts as needed | [packages/gavio-java](./packages/gavio-java/README.md) · [docs/packages/java.md](./docs/packages/java.md) |

---

## Examples

Runnable examples live in [examples/](./examples/). Each row is the same
scenario implemented per SDK wherever that surface exists, so the APIs can be
compared side by side.

| # | Scenario | Python | JavaScript | Java | Needs a key? |
|---|---|---|---|---|---|
| 01 | Quickstart — PII redact + restore, audit, cost | [py](./examples/python/01-quickstart/) | [js](./examples/javascript/01-quickstart/) | [java](./examples/java/01-quickstart/) | no |
| 02 | Production gateway — audit, PII guard, timeout, retry | [py](./examples/python/02-production-gateway/) | [js](./examples/javascript/02-production-gateway/) | [java](./examples/java/02-production-gateway/) | optional |
| 03 | Custom scanner — write and test a `PiiScanner` | [py](./examples/python/03-custom-scanner/) | [js](./examples/javascript/03-custom-scanner/) | [java](./examples/java/03-custom-scanner/) | no |
| 04 | Production core stack — audit chain, PII, rate limit, guardrails, cache | [py](./examples/python/04-production-stack/) | — | — | no |
| 05 | Inspector & multi-agent tracing — waterfall, PII diff, agent DAG, sessions | [py](./examples/python/05-inspector/) | [js](./examples/javascript/05-inspector/) | — | no |
| 06 | Policy Packs — core PII + FinTech + custom regex pack | [py](./examples/python/06-policy-packs/) | [js](./examples/javascript/06-policy-packs/) | [java](./examples/java/06-policy-packs/) | no |
| 07 | Tool Runtime — schema, freshness, conflicts, permissions, approvals, replay | [py](./examples/python/07-tool-runtime/) | [js](./examples/javascript/07-tool-runtime/) | [java](./examples/java/07-tool-runtime/) | no |
| 08 | Runtime Export — metadata-safe runtime events and JSONL export | [py](./examples/python/08-runtime-export/) | [js](./examples/javascript/08-runtime-export/) | [java](./examples/java/08-runtime-export/) | no |
| 09 | Prompt Registry + Evals — versioned templates and metadata-safe reports | [py](./examples/python/09-prompt-registry-evals/) | [js](./examples/javascript/09-prompt-registry-evals/) | [java](./examples/java/09-prompt-registry-evals/) | no |
| 12 | Domain Policy Pack Catalog — signed packs, overrides, suppression | [py](./examples/python/12-domain-policy-packs/) | [js](./examples/javascript/12-domain-policy-packs/) | [java](./examples/java/12-domain-policy-packs/) | no |
| 13 | Self-hosted Control Plane — runtime config, policy source, cached fallback | [py](./examples/python/13-control-plane/) | [js](./examples/javascript/13-control-plane/) | [java](./examples/java/13-control-plane/) | no |
| 14 | Production Trust Package — metadata-only release evidence bundle and verifier | [py](./examples/python/14-production-trust/) | [js](./examples/javascript/14-production-trust/) | [java](./examples/java/14-production-trust/) | no |
| 15 | Ecosystem integrations — compatibility matrix, metadata labels, adapter payloads, full-stack smoke | [py](./examples/integrations/) | — | — | no |
| 20 | Platform Runtime Profile — metadata-only readiness profile and deterministic gaps | [py](./examples/python/20-platform-runtime/) | — | — | no |
| 21 | Eval CI Gate — `gavio eval run`, prompt/eval links, baseline comparison, JSON/JUnit reports | [py](./examples/python/21-eval-ci-gate/) | — | — | no |
| 22 | Platform Feature Tour — all major v2.x surfaces in one offline project | [py](./examples/python/22-platform-feature-tour/) | — | — | no |
| 23 | Prompt Registry v2 — signed manifests, semver selectors, approvals, metadata-safe diffs | [py](./examples/python/23-prompt-registry-v2/) | — | — | no |

Example 02 uses a real provider if `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` is
set; otherwise it falls back to the mock provider. All other examples run with
no API key and no network.

---

## Packages

Gavio is a monorepo. Each SDK is independently versioned-in-lockstep and
published to its native registry.

### 🐍 Python — `gavio` (PyPI)

The **reference implementation**. Async-first (`await gw.complete(...)`), sync
wrapper (`complete_sync`), full type hints + `py.typed`. Zero mandatory deps;
optional extras include `gavio[redis]`, `gavio[presidio]`, `gavio[otel]`,
`gavio[elasticsearch]`, `gavio[pgvector]`, and `gavio[ocr]`.

```bash
pip install gavio==2.4.0
```

→ **[Full Python guide](./docs/packages/python.md)** · [package README](./packages/gavio-py/README.md)

### 🟨 JavaScript / TypeScript — `gavio` (npm)

Written in TypeScript, ships full type definitions, **dual ESM + CJS build**
with per-subpath `exports` for tree-shaking. Native `fetch`, `node:crypto`.
Node 18+, Deno, Bun.

```bash
npm install gavio@2.4.0
```

→ **[Full JavaScript guide](./docs/packages/javascript.md)** · [package README](./packages/gavio-js/README.md)

### ☕ Java — `io.github.manojmallick:gavio-*` (Maven Central)

Multi-artifact Maven project: `gavio-core` plus one artifact per interceptor
family (`gavio-interceptor-pii`, `-audit`, `-reliability`, `-cache`,
`-governance`, `-guardrails`, `-metrics`, `-quality`), one per provider
(`gavio-provider-openai`, `-anthropic`, `-gemini`, `-azure`, `-openrouter`,
`-ollama`), and `gavio-testing`. Immutable records + builders,
`CompletableFuture` async, Java 17+.

Quickstart stack:

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
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-interceptor-audit</artifactId>
  <version>2.4.0</version>
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
| Policy Pack architecture — core/FinTech manifests and custom regex-rule packs | `F-PACK-01/02/05` | 0.12.0 |
| Policy Pack Catalog — signed domain packs, load-by-name/path APIs, overrides, false-positive suppression | `F-PACK-01/02/05` | 1.6.0 |

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
| **Cost Governance v2** — policy/decision contracts, projected-spend controls, fallback/downgrade/dry-run actions, budget-aware reports | `F-COST-05` | **1.2.0** |

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
| OpenTelemetry-style span exporter and JSONL conversion | `F-OBS-07` | 1.3.0 |
| Prompt Registry + Evals — versioned templates, pass/fail cases, output hashes | `F-EVAL-01/02` | 1.4.0 |
| Eval Runner + CI Gates — `gavio eval run`, YAML/JSON suites, baseline comparison, JSON/JUnit reports | `F-EVAL-03` | 2.1.0 |
| Prompt Registry v2 — signed manifests, semver selectors, approval metadata, metadata-safe diffs | `F-EVAL-04` | 2.2.0 |
| Eval + Prompt Workflow — prompt/eval links, per-version gates, triage metadata, release bundles | `F-EVAL-05` | 2.4.0 |
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
| Runtime context fields — `tenant`, `feature`, `cost`, `retry`, `tools`, `policy` | `F-RT-01` | 0.13.0 |
| AI Request Runtime / Inspector positioning | `F-DOC-V4` | 0.13.0 |
| Tool Runtime — schema validation, freshness, conflict detection, provenance | `F-TOOL-01/02/03/04` | 0.14.0 |
| Tool Runtime v2 — registry-backed permissions, approval gates, replay, MCP metadata | `F-TOOL-05/06/07/08` | 1.5.0 |
| Self-hosted Control Plane — runtime config, policy rollout, budget config, audit search, snapshots | — | 1.7.0 |
| Control Plane Persistence — SQLite migrations, Postgres adapter path, durable runtime/admin records | `F-CP-01` | 2.3.0 |
| Production Trust Package — metadata-only release evidence bundle and verifier | `F-TRUST-01` | 1.8.0 |
| Ecosystem integration catalog — compatibility matrix, metadata helpers, JS subpath, and offline recipes | `F-INT-01` | 1.9.0 |
| Ecosystem adapter payloads — dependency-light payload fragments for LiteLLM, promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and Vercel AI SDK | `F-INT-02` | 2.5.0 |
| Platform Runtime Profile — metadata-only readiness score, platform surfaces, evidence, and gap checks | `F-PLAT-01` | 2.0.0 |
| Stable release gate — lockstep version checks, release hygiene, API stability and LTS policy | — | 1.0.0 |
| Runtime event/export contract — metadata-safe JSONL exporters and integration recipes | `F-EXP-01` | 1.1.0 |
| Cost Governance v2 CLI — `gavio cost report` over JSONL records and budget policies | `F-COST-05` | 1.2.0 |
| OTel conversion CLI — `gavio events convert --to otel-json` over runtime event JSONL | `F-OBS-07` | 1.3.0 |
| **Providers** — OpenAI · Anthropic · Gemini · Azure OpenAI · Ollama · Mock (all stdlib HTTP, no vendor SDKs) | — | 0.1–0.2 |
| **OpenRouter provider adapter** — direct OpenAI-compatible integration with attribution headers | `F-ADP-02` | 0.13.0 |

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
| [docs/runtime-events.md](./docs/runtime-events.md) | Runtime event/export contract and JSONL exporter |
| [docs/prompt-registry-evals.md](./docs/prompt-registry-evals.md) | Prompt Registry templates and metadata-safe eval reports |
| [docs/control-plane.md](./docs/control-plane.md) | Self-hosted Control Plane runtime config, policy rollout, budget config, and audit search |
| [docs/trust-package.md](./docs/trust-package.md) | Production trust bundle threat model, privacy boundary, and SDK APIs |
| [docs/integrations.md](./docs/integrations.md) | How Gavio fits beside gateway, observability, and eval tools |
| [docs/platform-runtime.md](./docs/platform-runtime.md) | Platform runtime readiness profile, metadata-only posture checks, and SDK APIs |
| [docs/otel-mapping.md](./docs/otel-mapping.md) | InspectorEvent → OpenTelemetry spans · [Grafana dashboard](./docs/grafana/gavio-dashboard.json) |
| [docs/packages/](./docs/packages/) | Deep guide per SDK |
| [examples/](./examples/) | Runnable example projects in all three languages |
| [spec/](./spec/) | Canonical JSON Schema data model |
| [test-vectors/](./test-vectors/) | Shared cross-SDK conformance cases |
| [STABILITY.md](./STABILITY.md) | API stability, LTS policy, and stable release gate |
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
├── examples/                 runnable cross-SDK examples
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
