import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { GavioRequest } from '../../src/request.js'
import { PromptLineage, RagChunk } from '../../src/types.js'
import {
  auditInterceptor,
  AuditRecord,
  type AuditSink,
} from '../../src/interceptors/audit/index.js'

class Collector implements AuditSink {
  records: AuditRecord[] = []
  async write(record: AuditRecord): Promise<void> {
    this.records.push(record)
  }
}

const lineage = () =>
  new PromptLineage({
    templateId: 'support-reply',
    templateVersion: 'v3',
    variables: { customer: 'Ada', tier: 'gold' },
    ragChunks: [
      new RagChunk({ source: 'doc://kb/refunds', chunkId: 'c1', score: 0.92 }),
      { source: 'doc://kb/shipping' },
    ],
  })

describe('prompt lineage (F-OBS-04)', () => {
  it('RagChunk carries a source reference only — never text', () => {
    const d = new RagChunk({ source: 'doc://kb/refunds', chunkId: 'c1', score: 0.92 }).toJSON()
    expect(d).toEqual({ source: 'doc://kb/refunds', chunkId: 'c1', score: 0.92 })
    expect('text' in d).toBe(false)
  })

  it('serialises to a nested object and coerces plain chunk inits', () => {
    const d = lineage().toJSON()
    expect(d.templateId).toBe('support-reply')
    expect(d.variables).toEqual({ customer: 'Ada', tier: 'gold' })
    expect(d.ragChunks[0]!.source).toBe('doc://kb/refunds')
    expect(d.ragChunks[1]).toEqual({ source: 'doc://kb/shipping', chunkId: null, score: null })
  })

  it('coerces a plain lineage init on the request and survives copyWithMessages', () => {
    const req = new GavioRequest({
      messages: [{ role: 'user', content: 'hi' }],
      model: 'mock',
      provider: 'mock',
      lineage: { templateId: 'greet' },
    })
    expect(req.lineage).toBeInstanceOf(PromptLineage)
    const copy = req.copyWithMessages([{ role: 'user', content: 'redacted' }])
    expect(copy.lineage).toBe(req.lineage)
  })

  it('flows lineage into the audit record', async () => {
    const sink = new Collector()
    const gw = new Gateway({ devMode: true }).use(auditInterceptor({ sink }))
    await gw.complete({ messages: [{ role: 'user', content: 'hi' }], lineage: lineage() })

    expect(sink.records).toHaveLength(1)
    const rec = sink.records[0]!
    expect(rec.lineage).not.toBeNull()
    expect(rec.lineage!.templateId).toBe('support-reply')
    const json = rec.toJSON() as { lineage: { ragChunks: Array<{ source: string }> } }
    expect(json.lineage.ragChunks[0]!.source).toBe('doc://kb/refunds')
  })

  it('serialises lineage as null when absent', async () => {
    const sink = new Collector()
    const gw = new Gateway({ devMode: true }).use(auditInterceptor({ sink }))
    await gw.complete({ messages: [{ role: 'user', content: 'hi' }] })

    const rec = sink.records[0]!
    expect(rec.lineage).toBeNull()
    expect((rec.toJSON() as { lineage: unknown }).lineage).toBeNull()
  })

  it('participates in the content hash', () => {
    const base = {
      traceId: 't1',
      provider: 'mock',
      model: 'mock',
      timestampUtc: '2026-07-01T00:00:00.000Z',
    }
    const without = new AuditRecord(base)
    const withLineage = new AuditRecord({ ...base, lineage: lineage() })
    const other = new AuditRecord({ ...base, lineage: new PromptLineage({ templateId: 'other' }) })

    expect(without.contentHash()).not.toBe(withLineage.contentHash())
    expect(withLineage.contentHash()).not.toBe(other.contentHash())
  })
})
