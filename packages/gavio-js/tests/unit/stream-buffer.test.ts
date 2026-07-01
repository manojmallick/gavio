import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import { Gateway } from '../../src/gateway.js'
import { StreamBuffer } from '../../src/interceptors/reliability/index.js'
import {
  auditInterceptor,
  type AuditSink,
  type AuditRecord,
} from '../../src/interceptors/audit/index.js'
import type { Interceptor } from '../../src/interceptors/base.js'
import type { InterceptorContext } from '../../src/context.js'
import type { GavioResponse } from '../../src/response.js'

class Collector implements AuditSink {
  records: AuditRecord[] = []
  async write(record: AuditRecord): Promise<void> {
    this.records.push(record)
  }
}

/** A post-interceptor that rewrites the response content. */
const shout: Interceptor = {
  name: 'shout',
  async after(response: GavioResponse, _ctx: InterceptorContext): Promise<GavioResponse> {
    response.content = response.content.toUpperCase()
    return response
  },
}

const collect = async (gen: AsyncGenerator<string>): Promise<string> => {
  let out = ''
  for await (const chunk of gen) out += chunk
  return out
}

describe('streaming reliability / StreamBuffer (F-REL-06)', () => {
  it('StreamBuffer accumulates chunks', () => {
    const buf = new StreamBuffer()
    expect(buf.text()).toBe('')
    expect(buf.length).toBe(0)
    buf.append('ab')
    buf.append('cd')
    expect(buf.text()).toBe('abcd')
    expect(buf.length).toBe(4)
  })

  it('emits the buffered content', async () => {
    const gw = new Gateway({ devMode: true })
    const full = await collect(gw.stream({ messages: [{ role: 'user', content: 'hi there' }] }))
    expect(full.trim()).toBe('[mock reply] hi there')
  })

  it('runs post-interceptors on the full buffered response', async () => {
    const sink = new Collector()
    const gw = new Gateway({ devMode: true }).use(auditInterceptor({ sink }))
    const full = await collect(gw.stream({ messages: [{ role: 'user', content: 'hello world' }] }))

    expect(sink.records).toHaveLength(1)
    const expected = createHash('sha256').update(full, 'utf-8').digest('hex')
    expect(sink.records[0]!.responseHash).toBe(expected)
    expect(sink.records[0]!.interceptorsFired).toContain('audit')
  })

  it('a post-interceptor rewrite is visible to the caller', async () => {
    const gw = new Gateway({ devMode: true }).use(shout)
    const full = await collect(gw.stream({ messages: [{ role: 'user', content: 'quiet' }] }))
    expect(full).toBe(full.toUpperCase())
    expect(full).toContain('[MOCK REPLY] QUIET')
  })
})
