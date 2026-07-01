// Gavio production gateway — a realistic interceptor stack (plain JS, ESM).
//
// audit (outermost) → PII guard → timeout → retry, in front of a real provider.
// Falls back to the mock provider when no key is set, so it always runs.
//
//   npm install
//   export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY (optional)
//   node gateway.mjs

import process from 'node:process'
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'
import { auditInterceptor } from 'gavio/interceptors/audit'
import { retryInterceptor, timeoutPolicy } from 'gavio/interceptors/reliability'
import { mockProvider } from 'gavio/testing'

function buildGateway() {
  let gw
  if (process.env.ANTHROPIC_API_KEY) {
    gw = new Gateway({ provider: 'anthropic', model: 'claude-sonnet-4-6' })
  } else if (process.env.OPENAI_API_KEY) {
    gw = new Gateway({ provider: 'openai', model: 'gpt-4o' })
  } else {
    console.log('[info] No API key set — using mockProvider so the demo still runs.\n')
    gw = new Gateway().withAdapter(mockProvider())
  }
  return gw
    .use(auditInterceptor({ sink: 'stdout' })) // outermost
    .use(piiGuard({ sensitivity: 'strict' })) // redact before egress
    .use(timeoutPolicy({ timeoutMs: 30_000 }))
    .use(retryInterceptor({ maxAttempts: 3, baseDelayMs: 500 }))
}

const gw = buildGateway()

const r = await gw.complete({
  messages: [
    { role: 'system', content: 'You are a concise billing assistant.' },
    { role: 'user', content: 'Summarise the account for jan@example.com.' },
  ],
  agentId: 'billing-agent',
  sessionId: 'sess-42',
})

console.log('\nReply       :', r.content)
console.log('Provider    :', r.provider, r.modelVersion)
console.log('Interceptors:', r.interceptorsFired)
console.log('Tokens      :', r.usage.totalTokens, ' Cost: $' + r.costUsd.toFixed(6))
