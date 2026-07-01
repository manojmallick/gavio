// Gavio custom PII scanner — detect a domain-specific identifier (plain JS, ESM).
//
// A PiiScanner is just an object: { entityType, scan(text, ctx) }. No classes,
// no TypeScript required.
//
//   npm install
//   node custom-scanner.mjs

import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'
import { emailScanner } from 'gavio/interceptors/pii/scanners'
import { GavioTestKit, mockProvider } from 'gavio/testing'

// Detects ING account numbers of the form NL##INGB##########.
const ingAccountScanner = {
  entityType: 'ING_ACCOUNT',
  tier: 1,
  scan(text, ctx) {
    const re = /\bNL\d{2}INGB\d{10}\b/g
    const out = []
    for (const m of text.matchAll(re)) {
      out.push({
        entityType: 'ING_ACCOUNT',
        start: m.index,
        end: m.index + m[0].length,
        value: m[0],
        confidence: 1.0,
        replacement: `[ING_ACCOUNT_${ctx.nextIndex('ING_ACCOUNT')}]`,
      })
    }
    return out
  },
}

// 1) Compose the custom scanner with a built-in one.
const gw = new Gateway({ devMode: true }).use(
  piiGuard({ scanners: [emailScanner(), ingAccountScanner] }),
)
const r = await gw.complete({
  messages: [{ role: 'user', content: 'email jan@example.com, pay ING NL20INGB0001234567' }],
})
console.log('Reply    :', r.content)
console.log('PII found:', r.audit.piiEntityTypes) // [ 'EMAIL', 'ING_ACCOUNT' ]

// 2) Test the scanner in isolation with GavioTestKit — no network.
const kit = new GavioTestKit({
  interceptors: [piiGuard({ scanners: [ingAccountScanner] })],
  provider: mockProvider({ response: 'processed [ING_ACCOUNT_1]' }),
})
const result = await kit.run({
  messages: [{ role: 'user', content: 'account NL20INGB0001234567 on file' }],
})
if (!result.piiDetected('ING_ACCOUNT')) throw new Error('expected ING_ACCOUNT detected')
if (result.preRequestText().includes('NL20INGB0001234567')) throw new Error('should be redacted')
console.log('\n✓ custom scanner test passed →', result.response.content)
