import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import {
  auditInterceptor,
  verifyChain,
  buildCallGraph,
  type AuditSink,
  type AuditRecord,
} from '../../src/interceptors/audit/index.js'
import {
  circuitBreaker,
  CircuitState,
  loadBalancer,
} from '../../src/interceptors/reliability/index.js'
import { InterceptorContext } from '../../src/context.js'
import { GavioRequest } from '../../src/request.js'
import { GavioResponse } from '../../src/response.js'
import { CircuitOpenError, ServerError } from '../../src/errors.js'
import { mockProvider } from '../../src/providers/mock.js'

class Collector implements AuditSink {
  records: AuditRecord[] = []
  async write(record: AuditRecord): Promise<void> {
    this.records.push(record)
  }
}

const req = () =>
  new GavioRequest({ messages: [{ role: 'user', content: 'hi' }], model: 'mock', provider: 'mock' })
const ctx = (r: GavioRequest) => new InterceptorContext({ traceId: r.traceId })
const ok = (r: GavioRequest) =>
  new GavioResponse({ traceId: r.traceId, content: 'ok', model: r.model, provider: 'mock' })
const fail = async (): Promise<GavioResponse> => {
  throw new ServerError('boom')
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

// ── Hash-chain audit (F-OBS-02) ──────────────────────────────────────────────
describe('hash-chain audit', () => {
  it('links records and verifies', async () => {
    const sink = new Collector()
    const gw = new Gateway({ devMode: true }).use(auditInterceptor({ sink, hashChain: true }))
    for (let i = 0; i < 3; i++) await gw.complete({ messages: [{ role: 'user', content: `m${i}` }] })
    expect(sink.records).toHaveLength(3)
    expect(sink.records[0]!.previousHash).toBe('')
    expect(verifyChain(sink.records)).toBe(true)
  })

  it('detects tampering', async () => {
    const sink = new Collector()
    const gw = new Gateway({ devMode: true }).use(auditInterceptor({ sink, hashChain: true }))
    for (let i = 0; i < 3; i++) await gw.complete({ messages: [{ role: 'user', content: `m${i}` }] })
    sink.records[1]!.costUsd = 999
    expect(verifyChain(sink.records)).toBe(false)
  })
})

// ── Multi-agent DAG (F-OBS-03) ───────────────────────────────────────────────
describe('multi-agent trace', () => {
  it('reconstructs the call graph', async () => {
    const sink = new Collector()
    const gw = new Gateway({ devMode: true }).use(auditInterceptor({ sink }))
    const root = await gw.complete({ messages: [{ role: 'user', content: 'orchestrate' }], agentId: 'orchestrator' })
    await gw.complete({ messages: [{ role: 'user', content: 'a' }], agentId: 'agent-a', parentTraceId: root.traceId })
    await gw.complete({ messages: [{ role: 'user', content: 'b' }], agentId: 'agent-b', parentTraceId: root.traceId })
    const roots = buildCallGraph(sink.records)
    expect(roots).toHaveLength(1)
    expect(roots[0]!.agentId).toBe('orchestrator')
    expect(new Set(roots[0]!.children.map((c) => c.agentId))).toEqual(new Set(['agent-a', 'agent-b']))
  })
})

// ── Circuit breaker (F-REL-03) ───────────────────────────────────────────────
describe('circuitBreaker', () => {
  it('opens after threshold and fast-fails', async () => {
    const cb = circuitBreaker({ failureThreshold: 3, recoveryTimeoutSeconds: 60 })
    for (let i = 0; i < 3; i++) {
      const r = req()
      await expect(cb.around(r, ctx(r), fail)).rejects.toBeInstanceOf(ServerError)
    }
    expect((cb as unknown as { currentState: string }).currentState).toBe(CircuitState.OPEN)
    let called = 0
    const r = req()
    await expect(
      cb.around(r, ctx(r), async (rr) => {
        called += 1
        return ok(rr)
      }),
    ).rejects.toBeInstanceOf(CircuitOpenError)
    expect(called).toBe(0)
  })

  it('recovers on success after the timeout', async () => {
    const cb = circuitBreaker({ failureThreshold: 1, recoveryTimeoutSeconds: 0.05 })
    let r = req()
    await expect(cb.around(r, ctx(r), fail)).rejects.toBeInstanceOf(ServerError)
    await sleep(60)
    r = req()
    const res = await cb.around(r, ctx(r), async (rr) => ok(rr))
    expect(res.content).toBe('ok')
    expect((cb as unknown as { currentState: string }).currentState).toBe(CircuitState.CLOSED)
  })
})

// ── Load balancer (F-REL-04) ─────────────────────────────────────────────────
describe('loadBalancer', () => {
  it('round-robins across adapters', async () => {
    const a = mockProvider({ response: 'from-a' })
    const b = mockProvider({ response: 'from-b' })
    const gw = new Gateway().withAdapter(a).use(loadBalancer([a, b]))
    const out: string[] = []
    for (let i = 0; i < 3; i++) out.push((await gw.complete({ messages: [{ role: 'user', content: 'x' }] })).content)
    expect(out).toEqual(['from-a', 'from-b', 'from-a'])
  })

  it('honours weights', async () => {
    const a = mockProvider({ response: 'a' })
    const b = mockProvider({ response: 'b' })
    const gw = new Gateway().withAdapter(a).use(loadBalancer([a, b], { weights: [2, 1] }))
    const out: string[] = []
    for (let i = 0; i < 3; i++) out.push((await gw.complete({ messages: [{ role: 'user', content: 'x' }] })).content)
    expect(out).toEqual(['a', 'a', 'b'])
  })
})
