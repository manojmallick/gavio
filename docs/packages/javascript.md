# JavaScript / TypeScript SDK (`gavio`)

> npm package `gavio` · Node 18+ · TypeScript · dual ESM + CJS · zero runtime deps

Source: [`packages/gavio-js`](../../packages/gavio-js/).

- [Install](#install)
- [Gateway API](#gateway-api)
- [Sub-path imports](#sub-path-imports)
- [Interceptors](#interceptors)
- [Providers](#providers)
- [Runtime export](#runtime-export)
- [Ecosystem Integrations](#ecosystem-integrations)
- [Platform Runtime Profile](#platform-runtime-profile)
- [Production Trust Package](#production-trust-package)
- [Prompt Registry + Evals](#prompt-registry--evals)
- [Testing](#testing)
- [Module format & runtimes](#module-format--runtimes)

---

## Install

```bash
npm install gavio        # or: pnpm add gavio · yarn add gavio · bun add gavio
```

Ships full type definitions — no `@types/gavio` needed.

---

## Gateway API

Object config + a fluent `.use()` / `.withAdapter()` chain. Everything is
`async` (Promise-based).

```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const gw = new Gateway({ provider: 'anthropic', model: 'claude-sonnet-4-6' })
  .use(piiGuard({ sensitivity: 'strict' }))

const r = await gw.complete({
  messages: [{ role: 'user', content: 'Hello' }],
  agentId: 'my-agent',
  parentTraceId: null,          // set for multi-agent DAG tracing
  sessionId: 'sess-123',
  options: { temperature: 0.7, maxTokens: 1000 },
})

r.content            // string, PII restored
r.costUsd            // number
r.traceId            // UUID v7
r.usage.totalTokens
r.interceptorsFired  // string[]
r.audit              // AuditRecord
```

**Constructor options:** `{ provider, model, devMode, dryRun, exporters }`.
**Chain methods:** `.use(interceptor)`, `.withAdapter(adapter)`.

- **`devMode: true`** → mock provider + stdout audit auto-wired.
- **`dryRun: true`** → interceptors log but never modify/block.

---

## Sub-path imports

Import only what you use (tree-shakeable):

```typescript
import { Gateway }          from 'gavio'
import { piiGuard }         from 'gavio/interceptors/pii'
import { emailScanner }     from 'gavio/interceptors/pii/scanners'
import { auditInterceptor } from 'gavio/interceptors/audit'
import { stdoutSink }       from 'gavio/interceptors/audit/sinks'
import { retryInterceptor, timeoutPolicy, fallbackChain } from 'gavio/interceptors/reliability'
import { toolRuntime }      from 'gavio/interceptors/tool-runtime'
import { jsonlRuntimeExporter, otelSpanExporter } from 'gavio/exporters'
import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio/trust'
import { EvalSuite, PromptRegistry, PromptTemplate } from 'gavio/prompts'
import { anthropicAdapter, openaiAdapter, openrouterAdapter } from 'gavio/providers'
import { GavioTestKit, mockProvider }      from 'gavio/testing'
```

---

## Interceptors

Interceptors are **factory functions** returning an interceptor object:

```typescript
import { piiGuard } from 'gavio/interceptors/pii'
import { auditInterceptor } from 'gavio/interceptors/audit'
import { retryInterceptor, timeoutPolicy } from 'gavio/interceptors/reliability'
import { toolRuntime } from 'gavio/interceptors/tool-runtime'

const gw = new Gateway({ provider: 'anthropic', model: 'claude-sonnet-4-6' })
  .use(auditInterceptor({ sink: 'stdout' }))              // outermost
  .use(piiGuard({ sensitivity: 'strict', mode: 'redact' }))
  .use(timeoutPolicy({ timeoutMs: 30_000 }))
  .use(retryInterceptor({ maxAttempts: 3, baseDelayMs: 500, jitter: true }))
```

`piiGuard` options: `scanners`, `sensitivity`, `mode`
(`'redact' | 'mask' | 'tag' | 'block'`), `restoreOnResponse`, `logEntityTypes`,
`dryRun`. See [interceptors.md](../interceptors.md).

### Tool Runtime

`toolRuntime()` validates tool metadata from `metadata.tools` before tool
outputs re-enter model context. It supports declared input/output schemas,
freshness/TTL checks, conflict detection across configured result keys,
confidence scoring, and provenance records under `ctx.tools.runtime`.
Tool Runtime v2 adds registry-backed permissions, approval gates, replay
records, and MCP metadata capture through the same `metadata.tools` object.

```typescript
const gw = new Gateway({ devMode: true })
  .use(toolRuntime({ onFailure: 'error' }))

await gw.complete({
  messages: [{ role: 'user', content: 'summarize inventory' }],
  metadata: { tools: { calls: [{
    id: 'inventory-1',
    name: 'inventory',
    source: 'warehouse',
    created_at: '2026-07-12T12:00:00Z',
    ttl_seconds: 60,
    result: { sku: 'SKU-1', quantity: 4 },
    output_schema: { required: ['sku', 'quantity'] },
  }] } },
})
```

### Policy packs (v0.12.0)

Policy packs expose scanner composition plus manifest metadata. Existing
scanner factories still work, but the built-in core and FinTech packs are now
first-class:

```typescript
import {
  piiGuard,
  corePolicyPack,
  fintechPolicyPack,
  customPolicyPack,
  policyPackScanners,
} from 'gavio/interceptors/pii'

const fintech = fintechPolicyPack()
console.log(fintech.manifest().detectors)

const custom = customPolicyPack({
  id: 'acme.internal',
  name: 'Acme Internal IDs',
  rules: [{ name: 'employee_id', entityType: 'EMPLOYEE_ID', pattern: '\\bEMP-[0-9]{6}\\b' }],
})

const guard = piiGuard({ scanners: policyPackScanners(corePolicyPack(), fintech, custom) })
```

---

## Providers

| Provider | `provider:` | Env var |
|---|---|---|
| Anthropic | `'anthropic'` | `ANTHROPIC_API_KEY` |
| OpenAI | `'openai'` | `OPENAI_API_KEY` |
| Gemini | `'gemini'` | `GEMINI_API_KEY` |
| Azure OpenAI | `'azure_openai'` | `AZURE_OPENAI_API_KEY` + `AZURE_OPENAI_ENDPOINT` |
| OpenRouter | `'openrouter'` | `OPENROUTER_API_KEY` |
| Ollama | `'ollama'` | — (local; `OLLAMA_HOST`) |
| Mock | dev mode / `mockProvider()` | — |

Gemini, Azure OpenAI, and Ollama were added in **v0.2.0**; OpenRouter was added
in **v0.13.0**.

```typescript
import { anthropicAdapter } from 'gavio/providers/anthropic'
const gw = new Gateway().withAdapter(anthropicAdapter({ apiKey: process.env.ANTHROPIC_API_KEY, timeoutMs: 30_000 }))
```

OpenRouter accepts direct adapter options for custom base URLs and optional
attribution headers:

```typescript
import { openrouterAdapter } from 'gavio/providers/openrouter'

const gw = new Gateway()
  .withAdapter(openrouterAdapter({
    apiKey: process.env.OPENROUTER_API_KEY,
    httpReferer: 'https://app.example',
    appTitle: 'Gavio',
  }))
```

Adapters use native `fetch` — no `node-fetch` or vendor SDK.

---

## Inspector

Enable the embedded pipeline visualizer (`F-DX-09/10`, off by default) and open
`http://127.0.0.1:7411` — live traces, waterfalls, PII diffs, agent call
graphs, replay, stats. Full guide: [docs/inspector.md](../inspector.md).

```typescript
const gw = new Gateway({ devMode: true, inspect: true })
```

The embedded server exposes the same JSON API in every SDK (`/api/traces`,
`/api/dag`, `/api/stats`, `/api/cost-report`, …); the store-backed
`gavio inspect --store` CLI is Python-only.

Cost Intelligence (v0.11.0) reads scalar labels from request metadata:

```typescript
await gw.complete({
  messages: [{ role: 'user', content: 'price this' }],
  metadata: { costDimensions: { tenant: 'acme', feature: 'claims', endpoint: '/chat' } },
})
```

Those labels can be used with `/api/stats?group_by=tenant` and
`/api/cost-report?group_by=feature`.

Cost Governance v2 (v1.2.0) adds policy/decision contracts and budget-aware
reports:

```typescript
import { budgetPolicyControl, type BudgetPolicy } from 'gavio/interceptors/governance'

const policy: BudgetPolicy = {
  id: 'tenant-monthly',
  scopeType: 'tenant',
  scopeValue: 'acme',
  window: 'monthly',
  limitUsd: 500,
  hardLimitAction: 'fallback',
  fallbackModel: 'gpt-4o-mini',
}

const gw = new Gateway({ devMode: true })
  .use(budgetPolicyControl({ policy, estimatedRequestCostUsd: 0.02 }))
```

## Runtime export

Runtime export (v1.1.0, `F-EXP-01`) writes the Inspector event envelope as
metadata-safe JSONL. Adding an exporter enables metadata-mode events without
starting the Inspector HTTP server.

```typescript
import { Gateway, jsonlRuntimeExporter } from 'gavio'

const gw = new Gateway({
  devMode: true,
  exporters: [jsonlRuntimeExporter({ path: 'runtime-events.jsonl' })],
})
```

The JSONL exporter strips `messages`, `content`, and `diff` by default, even if
the local Inspector runs in `full` mode. See [runtime events](../runtime-events.md)
and [integrations](../integrations.md).

Observability + OTel (v1.3.0, `F-OBS-07`) maps the same runtime events into
OpenTelemetry-style span JSON without adding mandatory OTel dependencies:

```typescript
import { Gateway, otelSpanExporter } from 'gavio'

const gw = new Gateway({
  exporters: [otelSpanExporter({
    path: 'otel-spans.jsonl',
    serviceName: 'checkout-api',
  })],
})
```

## Self-hosted Control Plane

Control Plane support (v1.7.0) loads runtime config from an optional
self-hosted server and caches the last successful config for offline
fail-open/fail-closed behavior. v2.3.0 adds durable JSON file, SQLite, and
Postgres storage modes to the control-plane app. v2.6.0 adds Enterprise Admin
v2 controls in the app while keeping the SDK runtime config contract unchanged.

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

Use `ControlPlaneClient` or `loadControlPlaneConfig` directly when you need to
inspect or preload the fetched config before constructing a gateway.

## Ecosystem Integrations

Ecosystem integration helpers (v1.9.0, `F-INT-01`) provide dependency-light
metadata labels and compatibility rows for common gateways, observability
tools, eval tools, frameworks, and provider SDKs. Ecosystem adapter helpers
(v2.5.0, `F-INT-02`) add metadata-only payload fragments for LiteLLM,
promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and the Vercel AI SDK.

```typescript
import { compatibilityMatrix, integrationAdapterPayload, integrationMetadata } from 'gavio/integrations'

const metadata = integrationMetadata('openlit', {
  tenant: 'acme',
  feature: 'support-chat',
  environment: 'prod',
})
const rows = compatibilityMatrix()
const adapter = integrationAdapterPayload(
  'openlit',
  { traceId: 'trace_123', data: { status: 'ok', provider: 'openai' } },
  { metadata: { ...metadata, prompt: 'raw prompt text' } },
)
```

## Platform Runtime Profile

Platform Runtime Profile support (v2.0.0, `F-PLAT-01`) summarizes production
readiness across runtime events, audit hashes, policy packs, cost governance,
tool runtime, and trust evidence without storing prompts or responses.

```typescript
import {
  buildPlatformRuntimeProfile,
  verifyPlatformRuntimeProfile,
} from 'gavio/platform-runtime'

const profile = buildPlatformRuntimeProfile({
  profileId: 'platform-prod-support',
  generatedAt: '2026-07-12T12:00:00Z',
  runtime: {
    environment: 'production',
    policySource: 'project:prod-support',
    eventExportMode: 'metadata_only',
  },
  surfaces: [
    'runtime_events',
    'audit_hashes',
    'policy_packs',
    'cost_governance',
    'tool_runtime',
    'trust_evidence',
  ],
  evidence: {
    auditChain: { recordCount: 42, verified: true },
    runtimeEvents: { eventCount: 168, contentFree: true },
  },
})

console.log(verifyPlatformRuntimeProfile(profile).valid)
```

See [Platform Runtime Profile](../platform-runtime.md) for the schema,
readiness scoring contract, and cross-SDK test vector.

## Production Trust Package

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

```typescript
import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio'

const bundle = buildProductionTrustBundle({
  bundleId: 'trust-prod-support-2026-07-12',
  generatedAt: '2026-07-12T12:00:00Z',
  release: { version: '2.7.0', tag: 'v2.7.0' },
  runtime: {
    environment: 'production',
    policySource: 'project:prod-support',
    eventExportMode: 'metadata_only',
  },
  auditRecords,
})

console.log(verifyProductionTrustBundle(bundle).valid)
```

See [Production Trust Package](../trust-package.md) for the bundle schema,
threat model, privacy boundary, and cross-SDK examples.

## Prompt Registry + Evals

Prompt Registry + Evals (v1.4.0, `F-EVAL-01/02`) renders versioned chat
templates with metadata-only `PromptLineage` and runs deterministic eval cases
without storing raw model output in reports.

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

const suite = new EvalSuite({
  id: 'support-smoke',
  cases: [{
    id: 'refund',
    templateId: 'support.reply',
    variables: { customer: 'Avery', topic: 'refund' },
    assertions: [{ type: 'contains', value: 'refund' }],
  }],
})

const report = await suite.run(registry, () => 'Avery refund approved')
console.log(report.score)
```

JavaScript v2.4.0 adds prompt-to-eval links, per-version regression gates,
failure triage metadata, and prompt release bundles for release evidence.

See [Prompt Registry + Evals](../prompt-registry-evals.md) for all SDKs and the
shared schemas.

## Embeddings

`gw.embed({ texts })` (`F-SEC-10`, since v0.9.0) runs embedding inputs through
the same interceptor pipeline as completions — PII is scanned and redacted
before the provider's embedding API is called; the response carries one vector
per input in `r.embeddings`.

---

## Testing

`GavioTestKit` + `mockProvider` (works with any runner; examples use vitest):

```typescript
import { describe, it, expect } from 'vitest'
import { GavioTestKit, mockProvider } from 'gavio/testing'
import { piiGuard } from 'gavio/interceptors/pii'

it('redacts and restores', async () => {
  const kit = new GavioTestKit({
    interceptors: [piiGuard()],
    provider: mockProvider({ response: 'done [EMAIL_1]' }),
  })
  const r = await kit.run({ messages: [{ role: 'user', content: 'to jan@example.com' }] })
  expect(kit.preRequestText()).not.toContain('jan@example.com')
  expect(kit.piiDetected('EMAIL')).toBe(true)
  expect(r.content).toBe('done jan@example.com')
})
```

```bash
cd packages/gavio-js
npm ci
npm run typecheck     # tsc --noEmit (strict)
npm test              # vitest, incl. shared cross-SDK vectors
npm run build         # dual ESM+CJS build → dist/
```

---

## Module format & runtimes

- **Dual build:** `import 'gavio'` (ESM) and `require('gavio')` (CJS) both work
  via the `exports` map; type defs resolve per sub-path.
- **Runtimes:** Node 18+ (primary), Deno 1.40+, Bun 1.0+. Cloudflare
  Workers / Vercel Edge run the core (fetch-based, no Node-only APIs).
- Uses `node:crypto` for SHA-256 audit hashes and monotonic UUID v7.
