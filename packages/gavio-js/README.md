# Gavio — JavaScript / TypeScript SDK

> AI request runtime and inspector for production systems. PII protection,
> audit trails, runtime events, reliability, cost intelligence, policy packs, and provider
> adapters as composable interceptors.

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

Tool Runtime (v0.14.0) reads `metadata.tools.calls`, validates declared
input/output schemas, checks result freshness, detects configured conflicts,
and records provenance in `ctx.tools.runtime`.

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
import { costControl }       from 'gavio/interceptors/governance'
import { guardrails }        from 'gavio/interceptors/guardrails'
import { jsonlRuntimeExporter } from 'gavio/exporters'
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
import { Gateway, jsonlRuntimeExporter } from 'gavio'

const gw = new Gateway({
  devMode: true,
  exporters: [jsonlRuntimeExporter({ path: 'runtime-events.jsonl' })],
})
```

Runtime export (v1.1.0) writes metadata-safe JSONL events for integrations. The
exporter strips `messages`, `content`, and `diff` by default, even when the
local Inspector is in full capture mode.

## What's inside

Every feature is an interceptor you compose explicitly — no hidden magic.

- **Privacy & security** — `piiGuard()` with Email, IBAN (mod-97), BSN
  (11-proef), CreditCard (Luhn), Phone, IP, SSN scanners and
  redact/mask/tag/block + restore (`F-SEC-01`); secret/credential scanner
  (`F-SEC-04`); `promptInjectionGuard()` (`F-SEC-05`); embedding call guard
  (`F-SEC-10`); Policy Pack manifests for core, FinTech, and custom regex-rule
  packs (`F-PACK-01/02/05`).
- **Reliability** — `retryInterceptor()` (`F-REL-01`), `fallbackChain()`
  (`F-REL-02`), `circuitBreaker()` (`F-REL-03`), `loadBalancer()` (`F-REL-04`),
  buffered streaming (`F-REL-06`), `timeoutPolicy()` (`F-REL-07`).
- **Caching** — `semanticCache()`: SHA-256 exact + semantic (cosine) cache with
  in-memory and Redis backends (`F-CACHE-01/02/03/04`).
- **Cost & governance** — per-request cost tracking (`F-GOV-01`),
  `costControl()` budget caps (`F-GOV-02`), `rateLimiter()` (`F-GOV-03`),
  `modelPolicy()` (`F-GOV-04`), `costRouter()` (`F-GOV-06`).
- **Observability** — `auditInterceptor()` with SHA-256 content hashes, never
  raw text (`F-OBS-01`), tamper-evident hash chain (`F-OBS-02`), multi-agent
  DAG tracing via `agentId`/`parentTraceId` (`F-OBS-03`), prompt lineage
  (`F-OBS-04`), Prometheus metrics (`F-OBS-08`), stdout sink.
- **Runtime export** — metadata-safe JSONL runtime events for gateway,
  observability, and eval integrations (`F-EXP-01`).
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
