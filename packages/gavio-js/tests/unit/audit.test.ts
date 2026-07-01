import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import { auditInterceptor, AuditRecord } from '../../src/interceptors/audit/index.js'
import { stdoutSink } from '../../src/interceptors/audit/sinks/stdout.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import { emailScanner } from '../../src/interceptors/pii/scanners/index.js'
import { GavioTestKit } from '../../src/testing/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import type { AuditSink } from '../../src/interceptors/audit/sink.js'
import type { AuditRecord as Rec } from '../../src/interceptors/audit/record.js'

const sha256 = (t: string) => createHash('sha256').update(t, 'utf-8').digest('hex')

describe('AuditRecord hashing', () => {
  it('hashes text with SHA-256', () => {
    expect(AuditRecord.hashText('hello')).toBe(sha256('hello'))
  })
})

describe('auditInterceptor', () => {
  it('records hashes and metadata, never raw content', async () => {
    const captured: Rec[] = []
    const sink: AuditSink = { async write(r) { captured.push(r) } }
    const kit = new GavioTestKit({
      interceptors: [
        auditInterceptor({ sink }),
        piiGuard({ scanners: [emailScanner()], logEntityTypes: false }),
      ],
      provider: mockProvider({ response: 'hello world' }),
    })
    const result = await kit.run({
      messages: [{ role: 'user', content: 'mail a@b.com' }],
    })

    expect(captured).toHaveLength(1)
    const rec = captured[0]!
    // hashes present, content absent
    expect(rec.promptHash).toMatch(/^[0-9a-f]{64}$/)
    expect(rec.responseHash).toBe(sha256('hello world'))
    const json = JSON.stringify(rec.toJSON())
    expect(json).not.toContain('a@b.com')
    expect(json).not.toContain('hello world')
    // pii metadata captured
    expect(rec.piiEntityTypes).toContain('EMAIL')
    expect(rec.piiEntityCounts['EMAIL']).toBe(1)
    expect(result.auditRecord).toBe(rec)
  })

  it('prompt hash reflects the redacted prompt, not the original', async () => {
    const captured: Rec[] = []
    const sink: AuditSink = { async write(r) { captured.push(r) } }
    const kit = new GavioTestKit({
      // audit runs before pii_guard.before? No: audit is outermost, runs first,
      // hashing the prompt AFTER pii redaction is the goal — so order audit
      // first means its before runs first (original). To hash redacted prompt,
      // pii must run before audit's before. Place pii first.
      interceptors: [
        piiGuard({ scanners: [emailScanner()], logEntityTypes: false }),
        auditInterceptor({ sink }),
      ],
      provider: mockProvider({ response: 'ok' }),
    })
    await kit.run({ messages: [{ role: 'user', content: 'mail a@b.com' }] })
    const rec = captured[0]!
    // the redacted prompt is 'mail [EMAIL_1]' (not the raw email), and the hash
    // equals the hash of the redacted text — never the original.
    expect(rec.promptHash).toBe(sha256('mail [EMAIL_1]'))
    expect(rec.promptHash).not.toBe(sha256('mail a@b.com'))
  })
})

describe('stdoutSink', () => {
  it('pretty-prints metadata without raw content', async () => {
    const lines: string[] = []
    const sink = stdoutSink({ pretty: true, write: (l) => lines.push(l) })
    const rec = new AuditRecord({
      traceId: '0192abcd-1234-7000-8000-000000000000',
      provider: 'mock',
      model: 'mock',
      timestampUtc: AuditRecord.nowUtc(),
      responseHash: sha256('x'),
      piiEntityTypes: ['EMAIL'],
      interceptorsFired: ['pii_guard', 'audit'],
    })
    await sink.write(rec)
    expect(lines[0]).toContain('[gavio:audit]')
    expect(lines[0]).toContain('pii=EMAIL')
    expect(lines[0]).toContain('mock/mock')
  })
})
