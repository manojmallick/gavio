import { describe, it, expect } from 'vitest'
import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Gateway } from '../../src/gateway.js'
import { auditInterceptor, AuditRecord, jsonlSink } from '../../src/interceptors/audit/index.js'
import { mockProvider } from '../../src/providers/mock.js'

function rec(subjectId: string | null, traceId = 't'): AuditRecord {
  return new AuditRecord({ traceId, provider: 'mock', model: 'm', timestampUtc: 'now', subjectId })
}

function gw() {
  return new Gateway({ model: 'mock' }).withAdapter(mockProvider({ response: 'hi' })).use(auditInterceptor())
}

describe('right-to-erasure (F-QUA-09)', () => {
  it('persists subjectId from request metadata into the audit record', async () => {
    const res = await gw().complete({
      messages: [{ role: 'user', content: 'q' }],
      metadata: { subject_id: 'user-123' },
    })
    expect(res.audit?.subjectId).toBe('user-123')
  })

  it('leaves subjectId null when absent', async () => {
    const res = await gw().complete({ messages: [{ role: 'user', content: 'q' }] })
    expect(res.audit?.subjectId).toBeNull()
  })

  it('jsonlSink.purge removes matching records and returns the count', async () => {
    const path = join(mkdtempSync(join(tmpdir(), 'gavio-erasure-')), 'audit.jsonl')
    const sink = jsonlSink({ path })
    await sink.write(rec('u1', 't1'))
    await sink.write(rec('u2', 't2'))
    await sink.write(rec('u1', 't3'))

    const removed = await sink.purge!('u1')

    expect(removed).toBe(2)
    const lines = readFileSync(path, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((l) => JSON.parse(l))
    expect(lines).toHaveLength(1)
    expect(lines[0].subjectId).toBe('u2')
  })

  it('purge returns 0 on a missing file and on no match', async () => {
    const path = join(mkdtempSync(join(tmpdir(), 'gavio-erasure-')), 'audit.jsonl')
    const sink = jsonlSink({ path })
    expect(await sink.purge!('nobody')).toBe(0) // file not yet written
    await sink.write(rec('u1'))
    expect(await sink.purge!('nobody')).toBe(0)
  })
})
