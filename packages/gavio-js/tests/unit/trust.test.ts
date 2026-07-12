import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { AuditRecord } from '../../src/interceptors/audit/index.js'
import {
  buildProductionTrustBundle,
  verifyProductionTrustBundle,
} from '../../src/trust.js'

const vector = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../test-vectors/trust/production-trust-bundle.json', import.meta.url),
    ),
    'utf8',
  ),
) as Record<string, unknown>

function records(): AuditRecord[] {
  const first = new AuditRecord({
    traceId: 'trace-a',
    provider: 'mock',
    model: 'mock',
    timestampUtc: '2026-07-12T12:00:00Z',
    promptHash: AuditRecord.hashText('prompt-a'),
    responseHash: AuditRecord.hashText('response-a'),
  })
  const second = new AuditRecord({
    traceId: 'trace-b',
    provider: 'mock',
    model: 'mock',
    timestampUtc: '2026-07-12T12:00:01Z',
    previousHash: first.contentHash(),
    promptHash: AuditRecord.hashText('prompt-b'),
    responseHash: AuditRecord.hashText('response-b'),
  })
  return [first, second]
}

describe('production trust bundles', () => {
  it('builds and verifies a metadata-only trust bundle', () => {
    const bundle = buildProductionTrustBundle({
      bundleId: 'trust-prod-support-2026-07-12',
      generatedAt: '2026-07-12T12:00:00Z',
      sdk: { name: 'gavio-js', version: '1.7.0' },
      release: { version: '1.7.0', tag: 'v1.7.0', commit: 'b1ff1be' },
      runtime: {
        environment: 'production',
        policySource: 'project:prod-support',
        controlPlaneEnabled: true,
        eventExportMode: 'metadata_only',
      },
      auditRecords: records(),
      runtimeEvents: [
        { type: 'trace.start', data: { provider: 'mock' } },
        { type: 'provider.call.end', data: { status: 'ok' } },
      ],
      controls: [
        {
          type: 'policy_pack',
          id: 'support',
          status: 'pass',
          source: 'test-vectors/policy-packs/catalog.json',
        },
      ],
      documents: [{ name: 'Threat model', path: 'docs/trust-package.md#threat-model' }],
    })

    const result = verifyProductionTrustBundle(bundle)

    expect(result.valid).toBe(true)
    expect(result.errors).toEqual([])
    expect(bundle.bundleHash).toBe(result.computedHash)
    expect(bundle.evidence.auditChain.recordCount).toBe(2)
    expect(bundle.evidence.runtimeEvents.contentFree).toBe(true)
  })

  it('verifies the shared production trust vector', () => {
    const result = verifyProductionTrustBundle(vector)

    expect(result.valid).toBe(true)
    expect(result.computedHash).toBe(vector.bundleHash)
  })

  it('rejects tampered or content-bearing bundles', () => {
    const bundle = buildProductionTrustBundle({
      bundleId: 'trust-prod-support-2026-07-12',
      generatedAt: '2026-07-12T12:00:00Z',
      sdk: { name: 'gavio-js', version: '1.7.0' },
      release: { version: '1.7.0' },
      runtime: {
        environment: 'production',
        policySource: 'project:prod-support',
        eventExportMode: 'metadata_only',
      },
      auditRecords: records(),
    }) as Record<string, unknown>
    ;(bundle.release as Record<string, unknown>).version = '1.7.1'
    const runtimeEvents = (
      (bundle.evidence as Record<string, unknown>).runtimeEvents as Record<string, unknown>
    )
    runtimeEvents.contentFree = false
    runtimeEvents.content = 'raw prompt text'

    const result = verifyProductionTrustBundle(bundle)

    expect(result.valid).toBe(false)
    expect(result.errors).toContain('bundleHash does not match bundle content')
    expect(result.errors).toContain('bundle contains content-bearing keys')
    expect(result.errors).toContain('evidence.runtimeEvents.contentFree must be true')
  })
})
