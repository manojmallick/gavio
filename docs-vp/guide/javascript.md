---
description: "Gavio JavaScript/TypeScript SDK guide — install, Gateway API, dual ESM+CJS, sub-path imports, providers, testing. Node 18+, zero runtime deps."
---

# JavaScript / TypeScript SDK (`gavio`)

> npm package `gavio` · Node 18+ · TypeScript · dual ESM + CJS · zero runtime deps

Source: [`packages/gavio-js`](https://github.com/manojmallick/gavio/tree/main/packages/gavio-js).

- [Install](#install)
- [Gateway API](#gateway-api)
- [Sub-path imports](#sub-path-imports)
- [Interceptors](#interceptors)
- [Providers](#providers)
- [Ecosystem Integrations](#ecosystem-integrations)
- [Platform Runtime Profile](#platform-runtime-profile)
- [Production Trust Package](#production-trust-package)
- [Prompt Registry + Evals](#prompt-registry--evals)
- [Platform Workflow Release](#platform-workflow-release)
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
import { anthropicAdapter, openaiAdapter } from 'gavio/providers'
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
`dryRun`. See [interceptors.md](./interceptors.md).

### Loop guard (F-REL-09)

`loopGuard` stops a runaway agent. It counts the provider calls of one **root run** and, before
each call, compares the run with limits: refuse the call, reroute it to a cheaper model, or only
log the breach. Same behaviour as the Python SDK, pinned by the shared vectors.

```typescript
import { loopGuard } from 'gavio/interceptors/reliability'
import { LoopGuardError } from 'gavio'

const gw = new Gateway({ provider: 'openai', model: 'gpt-4o' })
  .use(auditInterceptor())
  .use(loopGuard({
    policy: {
      id: 'support-agent',
      maxSteps: 25,                // at most 25 provider calls in one run
      maxCostUsd: 1.0,             // stop once $1.00 of spend is recorded
      maxWallTimeSeconds: 120,     // from the run's first call
      maxRepeatedToolCalls: 3,     // same tool with the same arguments, three times
      action: 'stop',              // or 'fallback' (with fallbackModel) or 'dry_run'
    },
  }))

const root = await gw.complete({ messages, agentId: 'planner' })
try {
  for (;;) await gw.complete({ messages, parentTraceId: root.traceId })
} catch (error) {
  if (error instanceof LoopGuardError) console.log(error.decision.breached) // 'max_steps' on the 26th call
}
```

A run is `metadata.runId` when you set one, else the root of the `parentTraceId` chain, else the
call's own trace id — an agent loop has to pass one of the first two, because calls with neither
are separate runs that no limit can link. Each limit is optional and is checked *before* a call:
`maxSteps: 25` allows 25 calls; cost is only known afterwards, so the call that crosses
`maxCostUsd` still happens and the next one is refused. A repeated tool call is the same tool name
and the same arguments (hashed, key order ignored) requested `maxRepeatedToolCalls` times in the
run. A call that fails at the provider still counts as a step. `stop` throws `LoopGuardError`
(`error.decision` has the verdict: which limit, steps, cost, elapsed time — never prompts or tool
arguments); `fallback` lets the call through on `fallbackModel`; `dryRun` on the gateway, or
`action: 'dry_run'`, only records.

Each guarded call's audit record gets a `loopGuard` entry (policy, run id, action, the limit
reached, steps, cost, elapsed seconds — counts only), omitted when no guard is installed so hash
chains written before it still verify; breaches also show in the Inspector. State is in memory per
guard and bounded (`new LoopGuardStore({ maxRuns: 10_000, ttlSeconds: 3600 })`: least recently
used runs are evicted, idle runs forgotten). `evaluateLoopGuard`, `LoopGuardState` and
`RootRunResolver` (from `gavio`) are the pure parts. Semantics: `spec/README.md` ('Loop guard').

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
| Ollama | `'ollama'` | — (local; `OLLAMA_HOST`) |
| Mock | dev mode / `mockProvider()` | — |

Gemini, Azure OpenAI, and Ollama were added in **v0.2.0**.

```typescript
import { anthropicAdapter } from 'gavio/providers/anthropic'
const gw = new Gateway().withAdapter(anthropicAdapter({ apiKey: process.env.ANTHROPIC_API_KEY, timeoutMs: 30_000 }))
```

Adapters use native `fetch` — no `node-fetch` or vendor SDK.

### Tool calling (F-ADP-03)

OpenAI, Azure OpenAI, OpenRouter, Anthropic, Gemini, and Ollama share one tool-calling
shape. Declare `tools` (the same `ToolDefinition` objects Tool Runtime uses), read
`response.toolCalls`, and answer with tool-role messages — the adapter translates to and
from each provider's native format, parallel calls included.

```typescript
const tools = [{
  name: 'get_weather',
  description: 'Current weather',
  inputSchema: { type: 'object', properties: { city: { type: 'string' } }, required: ['city'] },
}]
const messages: Message[] = [{ role: 'user', content: 'Weather in Paris and Rome?' }]

let response = await gw.complete({ messages, tools, toolChoice: 'auto' })
while (response.toolCalls.length > 0) {        // parallel calls arrive together
  messages.push(response.toAssistantMessage())
  for (const call of response.toolCalls) {     // ToolCall { id, name, arguments }
    messages.push(call.result(await runTool(call.name, call.arguments)).toMessage())
  }
  response = await gw.complete({ messages, tools })
}
```

`toolChoice` is `'auto'`, `'none'`, `'required'`, or `{ name: 'get_weather' }`;
`response.finishReason` is a normalised `FinishReason`. Gemini and Ollama send no call ids,
so Gavio assigns `call_<index>_<name>`. Messages use the spec's camelCase keys
(`toolCalls`, `toolCallId`, `isError`); the Python spellings (`tool_calls`, `tool_call_id`,
`is_error`) are accepted when reading.

Privacy applies to tool traffic too: `piiGuard` and the secret scanner cover tool-call
arguments and tool-role messages before the provider payload is built, and audit records keep
only the tool name and an arguments hash (`toolCalls[].argumentsHash`, omitted when the
response had no tool calls). To validate what the model asked for, pass the calls to Tool
Runtime: `analyzeProviderToolCalls(response.toolCalls, toolsContext, { definitions: tools })`
from `gavio/interceptors/tool-runtime`. Cached responses never replay a tool call, and
requests that declare `tools` skip the semantic cache level.

The pure translators are exported from `gavio/providers` as `openaiToProviderPayload` /
`openaiFromProviderResponse` (OpenRouter uses the same pair), `azureOpenai…`, `anthropic…`,
`gemini…`, and `ollama…` — no network involved. Arguments are hashed over canonical JSON, so
keep integers and strings in arguments you intend to audit: JavaScript cannot tell `1.0` from
`1`, and numbers beyond 2^53 lose precision.

Not yet supported: streaming tool-call deltas (`gw.stream()` throws a `ConfigurationError`
if you pass `tools`, rather than dropping them), and provider-opaque per-call state such as
Gemini thought signatures. The Ollama mapping follows its documented `/api/chat` shape and has
not been exercised against a live server; Ollama has no `toolChoice`, so only `'auto'` is
accepted there.

---

## Inspector

Enable the embedded pipeline visualizer (`F-DX-09/10`, off by default) and open
`http://127.0.0.1:7411` — live traces, waterfalls, PII diffs, agent call
graphs, replay, stats. Full guide: [Inspector](./inspector.md).

```typescript
const gw = new Gateway({ devMode: true, inspect: true })
```

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

See [Platform Runtime Profile](./platform-runtime.md) for the schema, readiness
scoring contract, and cross-SDK test vector.

## Production Trust Package

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

```typescript
import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio'

const bundle = buildProductionTrustBundle({
  bundleId: 'trust-prod-support-2026-07-12',
  generatedAt: '2026-07-12T12:00:00Z',
  release: { version: '3.2.1', tag: 'v3.2.1' },
  runtime: {
    environment: 'production',
    policySource: 'project:prod-support',
    eventExportMode: 'metadata_only',
  },
  auditRecords,
})

console.log(verifyProductionTrustBundle(bundle).valid)
```

See [Production Trust Package](./trust-package.md) for the bundle schema,
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

JavaScript v2.4.0 adds prompt-to-eval links, per-version regression gates,
failure triage metadata, and prompt release bundles for release evidence.

## Platform Workflow Release

The platform workflow release (`F-DX-13`, Python since v3.0.0) is ported to
JavaScript: one metadata-only artifact joins prompt release bundles, eval
reports, policy-pack signature evidence, production trust verification, and
platform-runtime readiness. For the same inputs the artifact and its
`workflowHash` are identical to the Python reference.

```typescript
import { runPlatformWorkflowReleaseFile } from 'gavio/workflow'

const result = await runPlatformWorkflowReleaseFile('workflow.json')
console.log(result.passed, result.artifact.workflowHash)
```

`buildPlatformWorkflowRelease(manifest, { basePath })` builds the same artifact
from an in-memory manifest, and `runEvalFile` (from `gavio/prompts/runner`) is
the library eval runner the workflow uses. The `gavio workflow release` CLI and
YAML manifests stay Python-only; JavaScript reads JSON documents.

See [Platform Workflow Release](./platform-workflow-release.md) for the manifest format, the canonical
hash definition, and the shared test vector.

## Embeddings

`gw.embed({ texts })` (`F-SEC-10`, since v0.9.0) runs embedding inputs through
the same interceptor pipeline as completions — PII is scanned and redacted
before the provider's embedding API is called; one vector per input in
`r.embeddings`.

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
