# Gavio — JavaScript / TypeScript SDK

> AI request runtime and inspector for production systems. PII protection,
> audit trails, runtime events, reliability, cost intelligence, policy packs,
> production trust packages, platform runtime profiles, and provider adapters
> as composable interceptors.

`gavio` sits between your application and any LLM provider. The same request
passes through a pre/post interceptor chain — PII redaction, retries, caching,
budgets, audit logging, tool runtime, runtime events, runtime context — before and after the provider call. Same API in
[Python, Java, and JavaScript](https://github.com/manojmallick/gavio), enforced
by shared cross-SDK test vectors.

Part of the [Gavio](https://manojmallick.github.io/gavio) project. MIT licensed.
Written in TypeScript, ships full type definitions and dual ESM + CJS builds.
Node.js 18+ (native `fetch`, `node:crypto`).

## Install

```bash
npm install gavio        # zero runtime dependencies
```

## Quick start (dev mode — no API key, no network)

```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const gw = new Gateway({ devMode: true })   // MockProvider + stdout audit
  .use(piiGuard())                          // redact PII before it leaves the process

const r = await gw.complete({
  messages: [{ role: 'user', content: 'Email jan@example.com re NL91ABNA0417164300' }],
  agentId: 'demo',
})

console.log(r.content)               // PII restored in the reply
console.log(`cost=$${r.costUsd.toFixed(6)} latency=${r.latencyMs}ms`)
console.log('pii types:', r.audit?.piiEntityTypes)
```

## Real providers

```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'
import { auditInterceptor } from 'gavio/interceptors/audit'
import { retryInterceptor, timeoutPolicy } from 'gavio/interceptors/reliability'

const gw = new Gateway({ provider: 'anthropic', model: 'claude-sonnet-4-6' }) // reads ANTHROPIC_API_KEY
  .use(piiGuard({ sensitivity: 'strict' }))
  .use(auditInterceptor({ sink: 'stdout' }))
  .use(timeoutPolicy({ timeoutSeconds: 30 }))
  .use(retryInterceptor({ maxAttempts: 3 }))

const r = await gw.complete({ messages: [{ role: 'user', content: 'Hi' }] })
```

OpenAI, Gemini, Azure OpenAI, OpenRouter, and Ollama adapters work the same way
(`provider: 'openai' | 'gemini' | 'azure_openai' | 'openrouter' | 'ollama'`) —
switching providers is a config change, never an application change.

Streaming buffers the provider stream so post-interceptors (guardrails, PII
restore, audit) run on the complete response before any chunk reaches you:

```typescript
for await (const chunk of gw.stream({ messages: [{ role: 'user', content: 'Hi' }] })) {
  process.stdout.write(chunk)
}
```

## Tool Runtime

```typescript
import { toolRuntime } from 'gavio/interceptors/tool-runtime'

const gw = new Gateway({ devMode: true }).use(toolRuntime())
```

Tool Runtime reads `metadata.tools.calls`, validates declared input/output
schemas, checks result freshness, detects configured conflicts, and records
provenance in `ctx.tools.runtime`. Tool Runtime v2 also understands
`definitions`, `permissions`, `approvals`, `records`, and MCP metadata.

Embeddings run through the same pipeline — inputs are PII-scanned before the
provider's embedding API is called:

```typescript
const r = await gw.embed({ texts: ['index this: contact jan@example.com'] })
console.log(r.embeddings?.length)    // one vector per input, PII never left
```

## Sub-path imports (tree-shaking)

```typescript
import { Gateway }           from 'gavio'
import { piiGuard }          from 'gavio/interceptors/pii'
import { auditInterceptor }  from 'gavio/interceptors/audit'
import { retryInterceptor }  from 'gavio/interceptors/reliability'
import { semanticCache }     from 'gavio/interceptors/cache'
import { costControl, budgetPolicyControl } from 'gavio/interceptors/governance'
import { guardrails }        from 'gavio/interceptors/guardrails'
import { jsonlRuntimeExporter, otelSpanExporter } from 'gavio/exporters'
import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio/trust'
import { anthropicAdapter }  from 'gavio/providers/anthropic'
import { openrouterAdapter } from 'gavio/providers/openrouter'
import { GavioOpenAI }       from 'gavio/shim/openai'
import { GavioTestKit }      from 'gavio/testing'
```

## The Inspector

An embedded, zero-dependency visualizer for the pipeline: live traces,
per-interceptor waterfalls, PII redaction diffs, multi-agent call graphs,
replay, RED stats, and a read-only production dashboard.

```typescript
const gw = new Gateway({ devMode: true, inspect: true })
// open http://127.0.0.1:7411 and send a request
```

`GAVIO_INSPECT=1` enables it via the environment. Capture modes: `full`
(dev-mode default), `redacted`, and `metadata` (default outside dev mode —
no content, no replay). The `gavio inspect --store` CLI for JSONL audit files
is Python-only; the JS inspector serves the same dashboard endpoints from its
embedded server.

## Runtime export

```typescript
import { Gateway, jsonlRuntimeExporter, otelSpanExporter } from 'gavio'

const gw = new Gateway({
  devMode: true,
  exporters: [
    jsonlRuntimeExporter({ path: 'runtime-events.jsonl' }),
    otelSpanExporter({ path: 'otel-spans.jsonl', serviceName: 'checkout-api' }),
  ],
})
```

Runtime export (v1.1.0) writes metadata-safe JSONL events for integrations. The
exporter strips `messages`, `content`, and `diff` by default, even when the
local Inspector is in full capture mode. Observability + OTel (v1.3.0) maps
the same stream into OpenTelemetry-style span JSON (`F-OBS-07`).

## Ecosystem integrations

```typescript
import { compatibilityMatrix, integrationMetadata } from 'gavio/integrations'

const metadata = integrationMetadata('openlit', {
  tenant: 'acme',
  feature: 'support-chat',
  environment: 'prod',
})
const rows = compatibilityMatrix()
```

Ecosystem integration helpers (v1.9.0, `F-INT-01`) provide dependency-light
metadata labels and compatibility rows for common gateways, observability
tools, eval tools, frameworks, and provider SDKs.

## Platform Runtime Profile

```typescript
import { buildPlatformRuntimeProfile } from 'gavio/platform-runtime'

const profile = buildPlatformRuntimeProfile({
  profileId: 'platform-prod-support',
  generatedAt: '2026-07-12T12:00:00Z',
  runtime: { environment: 'production', eventExportMode: 'metadata_only' },
  surfaces: [
    'runtime_events',
    'audit_hashes',
    'policy_packs',
    'cost_governance',
    'tool_runtime',
    'trust_evidence',
  ],
})
```

Platform Runtime Profile support (v2.0.0, `F-PLAT-01`) creates deterministic,
metadata-only readiness reports for production runtime posture.

## Self-hosted Control Plane

```typescript
import { Gateway } from 'gavio'

const gw = await Gateway.fromConfig({
  devMode: true,
  control_plane: {
    url: 'http://127.0.0.1:8787',
    runtime_key: runtimeKey,
    policy_source: 'project:prod-support',
    fail_mode: 'open',
  },
})
```

Control Plane support (v1.7.0) loads runtime config from an optional
self-hosted server, caches the last successful config, and can fail open or
closed during outages. v2.3.0 adds durable JSON file, SQLite, and Postgres
storage modes to the control-plane app. The same surface is available as
`ControlPlaneClient` and `loadControlPlaneConfig`.

## Production Trust Package

```typescript
import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio'

const bundle = buildProductionTrustBundle({
  bundleId: 'trust-prod-support-2026-07-12',
  generatedAt: '2026-07-12T12:00:00Z',
  release: { version: '2.3.0', tag: 'v2.3.0' },
  runtime: {
    environment: 'production',
    policySource: 'project:prod-support',
    eventExportMode: 'metadata_only',
  },
  auditRecords,
})

console.log(verifyProductionTrustBundle(bundle).valid)
```

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

## Prompt Registry + Evals

```typescript
import { EvalSuite, PromptRegistry, PromptTemplate } from 'gavio/prompts'

const registry = new PromptRegistry([
  new PromptTemplate({
    id: 'support.reply',
    version: '2026-07-12',
    messages: [
      { role: 'system', content: 'You are concise.' },
      { role: 'user', content: 'Reply to {{ customer }} about {{ topic }}.' },
    ],
    requiredVariables: ['customer', 'topic'],
  }),
])

const report = await new EvalSuite({
  id: 'support-smoke',
  cases: [{
    id: 'refund',
    templateId: 'support.reply',
    variables: { customer: 'Avery', topic: 'refund' },
    assertions: [{ type: 'contains', value: 'refund' }],
  }],
}).run(registry, () => 'Avery refund approved')
```

Prompt Registry + Evals (v1.4.0) adds versioned prompt templates,
metadata-only lineage, deterministic pass/fail reports, and SHA-256 output
hashes instead of raw model output (`F-EVAL-01/02`).

JavaScript v2.2.0 adds Prompt Registry v2 manifests with signed file loading,
semantic-version selectors, approval metadata, and metadata-safe diffs
(`F-EVAL-04`).

## What's inside

Every feature is an interceptor you compose explicitly — no hidden magic.

- **Privacy & security** — `piiGuard()` with Email, IBAN (mod-97), BSN
  (11-proef), CreditCard (Luhn), Phone, IP, SSN scanners and
  redact/mask/tag/block + restore (`F-SEC-01`); secret/credential scanner
  (`F-SEC-04`); `promptInjectionGuard()` (`F-SEC-05`); embedding call guard
  (`F-SEC-10`); Policy Pack manifests for core, FinTech, custom regex-rule
  packs, and the signed domain catalog with load/override/signature APIs
  (`F-PACK-01/02/05`).
- **Reliability** — `retryInterceptor()` (`F-REL-01`), `fallbackChain()`
  (`F-REL-02`), `circuitBreaker()` (`F-REL-03`), `loadBalancer()` (`F-REL-04`),
  buffered streaming (`F-REL-06`), `timeoutPolicy()` (`F-REL-07`).
- **Caching** — `semanticCache()`: SHA-256 exact + semantic (cosine) cache with
  in-memory and Redis backends (`F-CACHE-01/02/03/04`).
- **Cost & governance** — per-request cost tracking (`F-GOV-01`),
  `costControl()` budget caps (`F-GOV-02`), `rateLimiter()` (`F-GOV-03`),
  `modelPolicy()` (`F-GOV-04`), `costRouter()` (`F-GOV-06`), Cost Governance
  v2 budget policies, decisions, and reports (v1.2.0).
- **Observability** — `auditInterceptor()` with SHA-256 content hashes, never
  raw text (`F-OBS-01`), tamper-evident hash chain (`F-OBS-02`), multi-agent
  DAG tracing via `agentId`/`parentTraceId` (`F-OBS-03`), prompt lineage
  (`F-OBS-04`), Prometheus metrics (`F-OBS-08`), stdout sink.
- **Prompt Registry + Evals** — `PromptRegistry`, `PromptTemplate`, and
  `EvalSuite` via `gavio/prompts`, plus signed manifests, semantic-version
  selectors, approvals, and metadata-safe prompt diffs (`F-EVAL-01/02/04`).
- **Runtime export** — metadata-safe JSONL runtime events (`F-EXP-01`) and
  OpenTelemetry-style span JSON (`F-OBS-07`) for gateway, observability, and
  eval integrations.
- **Control Plane** — optional self-hosted runtime config with policy rollout,
  budget config, audit search, config snapshots, SDK cache fallback, durable
  SQLite/Postgres app storage, and `ControlPlaneClient` (v1.7.0).
- **Production Trust Package** — metadata-only release evidence bundles with
  deterministic hashes, privacy checks, audit-chain evidence, runtime-event
  evidence, and document/control pointers (`F-TRUST-01`).
- **Quality** — `guardrails()` with JSON-schema and regex validators
  (`F-QUA-01/02`), composite `riskScorer()` (`F-QUA-06`).
- **Inspector** — dev-time visualizer (`F-DX-09/10`), agent call graphs and
  session views (`F-OBS-10`), trace replay (`F-DX-11`), PII-sanitized
  test-case export (`F-DX-12`), read-only production dashboard (`F-DX-08`).
- **Developer experience** — dev mode (`F-DX-01`), dry-run (`F-DX-02`),
  `GavioTestKit` + `mockProvider` via `gavio/testing` (`F-DX-03`),
  `GavioOpenAI` drop-in shim via `gavio/shim/openai` (`F-DX-04`),
  `Gateway.fromConfig()` construction (`F-DX-05`).
- **Providers** — OpenAI, Anthropic, Gemini, Azure OpenAI, OpenRouter, Ollama,
  Mock.

See the [documentation site](https://manojmallick.github.io/gavio), the
[JavaScript guide](../../docs/packages/javascript.md), the runnable
[examples](../../examples/), and the [CHANGELOG](../../CHANGELOG.md) for
version-by-version detail.

## Scripts

```bash
npm run typecheck    # tsc --noEmit (strict)
npm test             # vitest run
npm run smoke        # build + dev-mode end-to-end check
npm run build        # compile ESM + CJS to dist/
```
