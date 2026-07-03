/**
 * Inspector (F-DX-09/F-DX-10) tests: shared cross-SDK event-sequence vectors,
 * the embedded HTTP server, and the capture-mode safety gates.
 *
 * Vector gateways use an explicit MockProvider adapter (not devMode, which
 * auto-wires an audit interceptor and would pollute the expected sequences),
 * so 'full' capture is acknowledged explicitly.
 */

import { describe, it, expect, afterEach } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Gateway } from '../../src/gateway.js'
import type { InterceptorContext } from '../../src/context.js'
import type { Interceptor } from '../../src/interceptors/base.js'
import { auditInterceptor, stdoutSink } from '../../src/interceptors/audit/index.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import { BaseProviderAdapter } from '../../src/providers/base.js'
import type { GavioRequest } from '../../src/request.js'
import type { GavioResponse } from '../../src/response.js'
import type { InspectorEvent, InspectorMode } from '../../src/inspector/index.js'

interface VectorEvent {
  type: string
  name?: string
  status?: string
  mutated?: boolean
}

interface VectorCase {
  id: string
  mode: InspectorMode
  interceptors: string[]
  request: { messages: Array<{ role: string; content: string }> }
  expectedEvents: VectorEvent[]
  forbiddenDataKeys?: string[]
  requireError?: boolean
}

const vectors: { cases: VectorCase[] } = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../test-vectors/inspector/event-sequences.json', import.meta.url)),
    'utf8',
  ),
)

const FACTORIES: Record<string, () => Interceptor> = {
  pii_guard: () => piiGuard(),
  audit: () => auditInterceptor({ sink: stdoutSink({ write: () => {} }) }),
}

class FailingProvider extends BaseProviderAdapter {
  get providerName(): string {
    return 'mock'
  }
  async complete(_request: GavioRequest): Promise<GavioResponse> {
    throw new Error('provider unavailable (vector requireError)')
  }
  async healthCheck(): Promise<boolean> {
    return false
  }
}

function buildGateway(c: VectorCase): Gateway {
  const gw = new Gateway({
    model: 'mock',
    inspect: { mode: c.mode, startServer: false, unsafeContentCaptureAck: true },
  }).withAdapter(c.requireError ? new FailingProvider() : mockProvider())
  for (const name of c.interceptors) gw.use(FACTORIES[name]!())
  return gw
}

describe('inspector event-sequence vectors (shared cross-SDK)', () => {
  for (const c of vectors.cases) {
    it(`case: ${c.id}`, async () => {
      const gw = buildGateway(c)
      const events: InspectorEvent[] = []
      gw.inspector!.bus.subscribe((e) => events.push(e))

      const run = gw.complete({ messages: c.request.messages })
      if (c.requireError) {
        await expect(run).rejects.toThrow()
      } else {
        await run
      }

      expect(events.map((e) => e.type)).toEqual(c.expectedEvents.map((e) => e.type))
      c.expectedEvents.forEach((exp, i) => {
        const data = events[i]!.data as Record<string, unknown>
        if (exp.name !== undefined) expect(data['name']).toBe(exp.name)
        if (exp.status !== undefined) expect(data['status']).toBe(exp.status)
        if (exp.mutated !== undefined) expect(data['mutated']).toBe(exp.mutated)
      })

      for (const forbidden of c.forbiddenDataKeys ?? []) {
        for (const e of events) {
          expect(Object.keys(e.data as Record<string, unknown>)).not.toContain(forbidden)
        }
      }

      let prevSeq = -1
      for (const e of events) {
        expect(e.schemaVersion).toBe('1.0')
        expect(e.traceId).toBeTruthy()
        expect(e.tNs).toBeGreaterThanOrEqual(0)
        expect(e.seq).toBeGreaterThan(prevSeq)
        prevSeq = e.seq
        if (e.type.endsWith('.end') && e.type !== 'trace.end') {
          const dur = (e.data as Record<string, unknown>)['durationUs']
          expect(typeof dur).toBe('number')
          expect(dur as number).toBeGreaterThanOrEqual(0)
        }
      }
    })
  }
})

describe('inspector HTTP server', () => {
  const gateways: Gateway[] = []
  afterEach(async () => {
    for (const gw of gateways.splice(0)) await gw.inspector?.close()
  })

  async function serve(config: Record<string, unknown> = {}): Promise<{ gw: Gateway; base: string }> {
    const gw = new Gateway({
      model: 'mock',
      inspect: { mode: 'metadata', port: 0, ...config },
    }).withAdapter(mockProvider())
    gateways.push(gw)
    const port = await gw.inspector!.ready()
    return { gw, base: `http://127.0.0.1:${port}` }
  }

  it('serves health, traces, trace detail, and the UI', async () => {
    const { gw, base } = await serve()
    await gw.complete({ messages: [{ role: 'user', content: 'ping' }], agentId: 'srv-test' })

    const health = await fetch(`${base}/api/health`)
    expect(health.status).toBe(200)
    expect(health.headers.get('x-gavio-inspector-mode')).toBe('metadata')
    const h = (await health.json()) as Record<string, unknown>
    expect(h['status']).toBe('ok')
    expect(h['sdk']).toBe('js')
    expect(h['mode']).toBe('metadata')

    const list = (await (await fetch(`${base}/api/traces`)).json()) as {
      traces: Array<{ traceId: string }>
    }
    expect(list.traces).toHaveLength(1)

    const detail = await fetch(`${base}/api/traces/${list.traces[0]!.traceId}`)
    expect(detail.status).toBe(200)
    const d = (await detail.json()) as { events: unknown[] }
    expect(d.events.length).toBeGreaterThan(0)

    expect((await fetch(`${base}/api/traces/no-such-trace`)).status).toBe(404)

    const ui = await fetch(`${base}/`)
    expect(ui.headers.get('content-type')).toContain('text/html')
    expect(await ui.text()).toContain('Gavio Inspector')

    const pipeline = (await (await fetch(`${base}/api/pipeline`)).json()) as Record<string, unknown>
    expect(pipeline['provider']).toBe('mock')
    expect(Array.isArray(pipeline['interceptors'])).toBe(true)
  })

  it('requires the bearer token when authToken is set', async () => {
    const { base } = await serve({ authToken: 'hunter2' })
    expect((await fetch(`${base}/api/health`)).status).toBe(401)
    const ok = await fetch(`${base}/api/health`, { headers: { authorization: 'Bearer hunter2' } })
    expect(ok.status).toBe(200)
  })
})

describe('inspector safety gates and buffer', () => {
  it("refuses 'full' outside dev mode without the acknowledgement", () => {
    expect(() => new Gateway({ model: 'mock', inspect: { mode: 'full' } })).toThrow(
      /unsafeContentCaptureAck/,
    )
  })

  it('allows full mode in dev mode without the acknowledgement', () => {
    const gw = new Gateway({ devMode: true, inspect: { mode: 'full', startServer: false } })
    expect(gw.inspector).not.toBeNull()
    expect(gw.inspector!.config.mode).toBe('full')
  })

  it('refuses non-loopback binds without an auth token', () => {
    expect(
      () => new Gateway({ model: 'mock', inspect: { mode: 'metadata', bind: '0.0.0.0' } }),
    ).toThrow(/authToken/)
  })

  it('is off by default — even in dev mode — and ctx.inspect stays harmless', async () => {
    const recorder: Interceptor = {
      name: 'recorder',
      before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
        ctx.inspect('recorder', { saw: true })
        return request
      },
    }
    const gw = new Gateway({ devMode: true }).use(recorder)
    expect(gw.inspector).toBeNull()
    const response = await gw.complete({ messages: [{ role: 'user', content: 'no inspector' }] })
    expect(response.content).toContain('no inspector')
  })

  it('surfaces ctx.inspect entries as the decision on that hook event', async () => {
    const decider: Interceptor = {
      name: 'decider',
      before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
        ctx.inspect('rule', 'r42')
        return request
      },
    }
    const gw = new Gateway({
      model: 'mock',
      inspect: { mode: 'metadata', startServer: false },
    })
      .withAdapter(mockProvider())
      .use(decider)
    const events: InspectorEvent[] = []
    gw.inspector!.bus.subscribe((e) => events.push(e))
    await gw.complete({ messages: [{ role: 'user', content: 'decide' }] })
    const end = events.find((e) => e.type === 'interceptor.before.end')!
    expect((end.data as Record<string, unknown>)['decision']).toEqual({ rule: 'r42' })
  })

  it('evicts the oldest trace beyond maxTraces', async () => {
    const gw = new Gateway({
      model: 'mock',
      inspect: { mode: 'metadata', startServer: false, maxTraces: 2 },
    }).withAdapter(mockProvider())
    const first = await gw.complete({ messages: [{ role: 'user', content: 'one' }] })
    await gw.complete({ messages: [{ role: 'user', content: 'two' }] })
    await gw.complete({ messages: [{ role: 'user', content: 'three' }] })
    const buffer = gw.inspector!.buffer
    expect(buffer.size).toBe(2)
    expect(buffer.get(first.traceId)).toBeUndefined()
  })

  it('enables via GAVIO_INSPECT=1', () => {
    process.env['GAVIO_INSPECT'] = '1'
    try {
      const gw = new Gateway({ model: 'mock', inspect: { startServer: false } })
      expect(gw.inspector).not.toBeNull()
      expect(gw.inspector!.config.mode).toBe('metadata')
    } finally {
      delete process.env['GAVIO_INSPECT']
    }
  })
})
