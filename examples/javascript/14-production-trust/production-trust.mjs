import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio'
import { AuditRecord } from 'gavio/interceptors/audit'

const first = new AuditRecord({
  traceId: 'trace-a',
  provider: 'mock',
  model: 'mock',
  timestampUtc: '2026-07-12T12:00:00Z',
  promptHash: AuditRecord.hashText('support question'),
  responseHash: AuditRecord.hashText('support answer'),
})
const second = new AuditRecord({
  traceId: 'trace-b',
  provider: 'mock',
  model: 'mock',
  timestampUtc: '2026-07-12T12:00:01Z',
  previousHash: first.contentHash(),
  promptHash: AuditRecord.hashText('handoff question'),
  responseHash: AuditRecord.hashText('handoff answer'),
})

const bundle = buildProductionTrustBundle({
  bundleId: 'trust-prod-support-2026-07-12',
  generatedAt: '2026-07-12T12:00:00Z',
  release: { version: '2.1.0', tag: 'v2.1.0', commit: 'b1ff1be' },
  runtime: {
    environment: 'production',
    policySource: 'project:prod-support',
    controlPlaneEnabled: true,
    eventExportMode: 'metadata_only',
  },
  auditRecords: [first, second],
  runtimeEvents: [
    { type: 'trace.start', data: { provider: 'mock' } },
    { type: 'provider.call.end', data: { status: 'ok' } },
    { type: 'trace.end', data: { status: 'ok', costUsd: 0.0 } },
  ],
  controls: [
    {
      type: 'policy_pack',
      id: 'support',
      status: 'pass',
      source: 'test-vectors/policy-packs/catalog.json',
    },
    {
      type: 'benchmark',
      id: 'inspector-overhead',
      status: 'pass',
      source: 'docs/gavio-1x-gap-closure-roadmap.md',
    },
  ],
  documents: [{ name: 'Threat model', path: 'docs/trust-package.md#threat-model' }],
})
const result = verifyProductionTrustBundle(bundle)

console.log('bundle:', bundle.bundleId)
console.log('hash  :', bundle.bundleHash)
console.log('valid :', result.valid)
console.log('events:', bundle.evidence.runtimeEvents.eventTypes.join(', '))
