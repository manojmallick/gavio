/**
 * Embedding call guard tests (F-SEC-10).
 *
 * The same PII pipeline that protects completions must run on embedding calls:
 * inputs are scanned/redacted before the provider's embedding API, governance
 * and audit interceptors fire, and the inspector traces the call. Redaction
 * cases come from //test-vectors/embedding/redaction.json, shared with the
 * other SDKs. Mirrors the Python `tests/unit/test_embedding_guard.py`.
 */

import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { ProviderError } from '../../src/errors.js'
import { Gateway } from '../../src/gateway.js'
import type { InspectorEvent } from '../../src/inspector/events.js'
import { auditInterceptor } from '../../src/interceptors/audit/index.js'
import type { AuditSink } from '../../src/interceptors/audit/sink.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import type { ProviderAdapter } from '../../src/providers/base.js'
import { mockProvider } from '../../src/providers/mock.js'
import type { GavioRequest } from '../../src/request.js'

const vectorsUrl = new URL('../../../../test-vectors/embedding/redaction.json', import.meta.url)
const CASES = (
  JSON.parse(readFileSync(fileURLToPath(vectorsUrl), 'utf8')) as {
    cases: Array<{
      id: string
      texts: string[]
      expected: {
        piiEntityTypes: string[]
        redactedContains: string[]
        redactedNotContains: string[]
      }
    }>
  }
).cases

/** Records the request as it reaches the embedding API (post-redaction). */
function capturingMockProvider(): { adapter: ProviderAdapter; embedded: () => GavioRequest } {
  const inner = mockProvider()
  let captured: GavioRequest | null = null
  const adapter: ProviderAdapter = {
    providerName: inner.providerName,
    complete: (request) => inner.complete(request),
    embed: (request) => {
      captured = request
      return inner.embed(request)
    },
    healthCheck: () => inner.healthCheck(),
  }
  return {
    adapter,
    embedded: () => {
      if (captured === null) throw new Error('embed was never called')
      return captured
    },
  }
}

// Cross-SDK parity anchor: sha256('alpha') bytes [0..8) / 255 — the exact
// numbers the Python MockProvider produces for the same text.
const ALPHA_VECTOR = [
  0.5568627450980392, 0.8274509803921568, 0.9647058823529412, 0.6784313725490196,
  0.40784313725490196, 0.3568627450980392, 0.5843137254901961, 0.6196078431372549,
]

describe('Gateway.embed', () => {
  it('returns one deterministic vector per text', async () => {
    const gw = new Gateway({ model: 'mock' }).withAdapter(mockProvider())
    const response = await gw.embed({ texts: ['alpha', 'beta', 'gamma'] })
    expect(response.embeddings).not.toBeNull()
    expect(response.embeddings).toHaveLength(3)
    for (const vector of response.embeddings!) expect(vector).toHaveLength(8)
    expect(response.content).toBe('')
    expect(response.usage.promptTokens).toBeGreaterThan(0)
    expect(response.usage.completionTokens).toBe(0)
    // Deterministic: same text, same vector — and byte-identical to Python.
    const again = await gw.embed({ texts: ['alpha'] })
    expect(again.embeddings![0]).toEqual(response.embeddings![0])
    expect(again.embeddings![0]).toEqual(ALPHA_VECTOR)
  })

  it('raises ProviderError for adapters without embeddings', async () => {
    const noEmbed: ProviderAdapter = {
      providerName: 'mock',
      complete: () => {
        throw new Error('not used')
      },
      healthCheck: async () => true,
    }
    const gw = new Gateway({ model: 'mock' }).withAdapter(noEmbed)
    await expect(gw.embed({ texts: ['anything'] })).rejects.toThrow(ProviderError)
    await expect(gw.embed({ texts: ['anything'] })).rejects.toThrow(
      'mock does not support embeddings',
    )
  })

  it('writes an audit record with PII metadata', async () => {
    const sink: AuditSink = { async write() {} }
    const gw = new Gateway({ model: 'mock' })
      .withAdapter(mockProvider())
      .use(auditInterceptor({ sink }))
      .use(piiGuard())
    const response = await gw.embed({ texts: ['reach me at jan.real@corp.com'] })
    const record = response.audit
    expect(record).not.toBeNull()
    expect(record!.traceId).toBe(response.traceId)
    expect(record!.promptHash).toBeTruthy()
    expect(record!.piiEntityTypes).toContain('EMAIL')
    expect(record!.interceptorsFired).toContain('pii_guard')
  })

  it('is traced by the inspector', async () => {
    const gw = new Gateway({
      model: 'mock',
      inspect: { mode: 'metadata', startServer: false },
    })
      .withAdapter(mockProvider())
      .use(piiGuard())
    const events: InspectorEvent[] = []
    gw.inspector!.bus.subscribe((event) => events.push(event))
    await gw.embed({ texts: ['mail jan@example.com please'] })
    const types = events.map((e) => e.type)
    expect(types[0]).toBe('trace.start')
    expect(types).toContain('provider.call.start')
    expect(types[types.length - 1]).toBe('trace.end')
    const end = events[events.length - 1]!.data as Record<string, unknown>
    expect(end['status']).toBe('ok')
    expect(end['piiEntityTypes']).toContain('EMAIL')
    await gw.inspector!.close()
  })
})

describe('shared test-vectors — embedding/redaction.json', () => {
  for (const c of CASES) {
    it(`${c.id}`, async () => {
      const { adapter, embedded } = capturingMockProvider()
      const gw = new Gateway({ model: 'mock' }).withAdapter(adapter).use(piiGuard())
      const response = await gw.embed({ texts: c.texts })

      const reachedProvider = embedded()
        .messages.map((m) => m.content ?? '')
        .join(' \n ')
      for (const fragment of c.expected.redactedContains) {
        expect(reachedProvider, `${c.id}: missing ${JSON.stringify(fragment)}`).toContain(fragment)
      }
      for (const raw of c.expected.redactedNotContains) {
        expect(reachedProvider, `${c.id}: leaked ${JSON.stringify(raw)}`).not.toContain(raw)
      }
      expect(response.embeddings).not.toBeNull()
      expect(response.embeddings).toHaveLength(c.texts.length)
    })
  }
})
