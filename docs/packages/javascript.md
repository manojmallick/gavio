# JavaScript / TypeScript SDK (`gavio`)

> npm package `gavio` · Node 18+ · TypeScript · dual ESM + CJS · zero runtime deps

Source: [`packages/gavio-js`](../../packages/gavio-js/).

- [Install](#install)
- [Gateway API](#gateway-api)
- [Sub-path imports](#sub-path-imports)
- [Interceptors](#interceptors)
- [Providers](#providers)
- [Runtime export](#runtime-export)
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
import { jsonlRuntimeExporter } from 'gavio/exporters'
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

### Tool Runtime (v0.14.0)

`toolRuntime()` validates tool metadata from `metadata.tools` before tool
outputs re-enter model context. It supports declared input/output schemas,
freshness/TTL checks, conflict detection across configured result keys,
confidence scoring, and provenance records under `ctx.tools.runtime`.

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
