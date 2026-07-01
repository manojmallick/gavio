# Gavio — JavaScript / TypeScript SDK

> The open standard AI gateway for production systems. PII protection, audit
> trails, reliability, and cost control as composable interceptors.

`gavio` sits between your application and any LLM provider. The same request
passes through a pre/post interceptor chain — PII redaction, retries, cost
tracking, audit logging — before and after the provider call.

Part of the [Gavio](https://gavio.io) project. MIT licensed. Written in
TypeScript, ships full type definitions, ESM. Node.js 18+ (native `fetch`,
`node:crypto`).

## Install

```bash
npm install gavio        # zero runtime dependencies
```

## Quick start (dev mode — no API key, no network)

```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const gw = new Gateway({ devMode: true }).use(piiGuard())

const r = await gw.complete({
  messages: [{ role: 'user', content: 'Email jan@example.com re NL91ABNA0417164300' }],
  agentId: 'demo',
})

console.log(r.content)               // PII restored in the reply
console.log(`cost=$${r.costUsd.toFixed(6)} latency=${r.latencyMs}ms`)
console.log('pii types:', r.audit.piiEntityTypes)
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
  .use(timeoutPolicy({ timeoutMs: 30_000 }))
  .use(retryInterceptor({ maxAttempts: 3 }))

const r = await gw.complete({ messages: [{ role: 'user', content: 'Hi' }] })
```

`OPENAI_API_KEY` / `provider: 'openai'` work the same way.

## Sub-path imports (tree-shaking)

```typescript
import { Gateway }          from 'gavio'
import { piiGuard }         from 'gavio/interceptors/pii'
import { auditInterceptor } from 'gavio/interceptors/audit'
import { retryInterceptor } from 'gavio/interceptors/reliability'
import { anthropicAdapter } from 'gavio/providers/anthropic'
import { GavioTestKit }     from 'gavio/testing'
```

## What ships in v0.1.0

- **Core** — `Gateway` (object config + `.use()` / `.withAdapter()`),
  `InterceptorChain` (onion pre/post model), `GavioRequest` / `GavioResponse`
  (camelCase), monotonic UUID v7 `traceId`, `agentId` / `parentTraceId`.
- **PII Guard (F-SEC-01)** — Email, IBAN (mod-97), BSN (11-proef),
  CreditCard (Luhn), Phone, IP (v4/v6), SSN scanners; redact / mask / tag /
  block; restore-on-response; overlap resolution.
- **Secret Scanner (F-SEC-04)** — API keys, JWTs, PEM keys, DB URLs.
- **Reliability** — `retryInterceptor` (F-REL-01), `fallbackChain` (F-REL-02),
  `timeoutPolicy` (F-REL-07).
- **Cost tracking (F-GOV-01)** — `costUsd` on every response.
- **Audit (F-OBS-01)** — `AuditRecord` (SHA-256 hashes, metadata only) +
  `stdoutSink` (F-OBS-05).
- **Dev mode (F-DX-01)** and **dry-run mode (F-DX-02)**.
- **Providers** — OpenAI, Anthropic (native `fetch`), Mock.
- **Testing** — `GavioTestKit`, `mockProvider`.

See the [JavaScript guide](../../docs/packages/javascript.md) and [CHANGELOG.md](../../CHANGELOG.md).

## Scripts

```bash
npm run typecheck    # tsc --noEmit (strict)
npm test             # vitest run  (59 unit tests)
npm run smoke        # build + dev-mode end-to-end check
npm run build        # compile to dist/
```
