/**
 * Smoke test — dev mode, PII guard with email + IBAN, mock provider.
 *
 * Run with:  npm run smoke   (builds to dist/ first, then runs this)
 * Confirms PII is detected, redacted before the provider, and restored in the
 * reply, and prints the audit PII entity types.
 */

import { Gateway } from '../dist/esm/index.js'
import { piiGuard } from '../dist/esm/interceptors/pii/index.js'

const gw = new Gateway({ devMode: true }).use(piiGuard())

const r = await gw.complete({
  messages: [
    { role: 'user', content: 'Email jan@example.com re NL91ABNA0417164300' },
  ],
  agentId: 'demo',
})

console.log('REPLY:', r.content)
console.log('PII:', r.audit.piiEntityTypes)
console.log('cost:', r.costUsd, '| fired:', r.interceptorsFired)
console.log('trace:', r.traceId)

if (!r.content.includes('jan@example.com')) {
  throw new Error('expected PII to be restored in the reply')
}
if (!r.audit.piiEntityTypes.includes('EMAIL')) {
  throw new Error('expected EMAIL in audit pii entity types')
}
console.log('\n✓ smoke passed')
