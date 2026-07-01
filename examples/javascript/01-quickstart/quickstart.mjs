// Gavio quickstart — PII redaction in dev mode. Plain JavaScript (ESM).
//
// No API key, no network: dev mode wires a mock provider + stdout audit.
//
//   npm install
//   node quickstart.mjs

import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const gw = new Gateway({ devMode: true }).use(piiGuard())

const r = await gw.complete({
  messages: [
    { role: 'user', content: 'Email jan@example.com about IBAN NL91ABNA0417164300' },
  ],
  agentId: 'quickstart',
})

// The email + IBAN were redacted before the mock provider, then restored.
console.log('\nReply    :', r.content)
console.log('PII found:', r.audit.piiEntityTypes)
console.log('Fired    :', r.interceptorsFired)
console.log('Cost     : $' + r.costUsd.toFixed(6) + '   (mock = free)')
console.log('Trace    :', r.traceId)
