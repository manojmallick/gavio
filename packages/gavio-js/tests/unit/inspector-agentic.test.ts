/**
 * Inspector v0.7.0 tests — DAG, sessions, stats, replay, simulate-cost, export.
 *
 * Covers F-OBS-10 (agent call graph + sessions), F-DX-11 (replay & edit-resend),
 * F-DX-08 (RED stats + hash-chain verify gate) and F-DX-12 (export trace as a
 * test case). Mirrors the Python `tests/unit/test_inspector_agentic.py`.
 */

import { describe, it, expect, afterEach } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { buildDag, buildSessions, buildStats } from '../../src/inspector/analytics.js'
import type { InspectorMode } from '../../src/inspector/index.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import { mockProvider } from '../../src/providers/mock.js'

const gateways: Gateway[] = []

afterEach(async () => {
  for (const gw of gateways.splice(0)) await gw.inspector?.close()
})

async function serve(mode: InspectorMode = 'full'): Promise<{ gw: Gateway; base: string }> {
  const gw = new Gateway({
    model: 'mock',
    inspect: { mode, port: 0, unsafeContentCaptureAck: true },
  }).withAdapter(mockProvider())
  gateways.push(gw)
  const port = await gw.inspector!.ready()
  return { gw, base: `http://127.0.0.1:${port}` }
}

async function getJson(base: string, path: string): Promise<Record<string, unknown>> {
  const response = await fetch(base + path)
  expect(response.status).toBe(200)
  return (await response.json()) as Record<string, unknown>
}

async function post(base: string, path: string, body: unknown): Promise<Response> {
  return fetch(base + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/** One orchestrator trace with two children in session s1. */
async function runFamily(gw: Gateway): Promise<[string, string, string]> {
  const root = await gw.complete({
    messages: [{ role: 'user', content: 'orchestrate' }],
    agentId: 'orchestrator',
    sessionId: 's1',
  })
  const childA = await gw.complete({
    messages: [{ role: 'user', content: 'sub-task a' }],
    agentId: 'worker-a',
    parentTraceId: root.traceId,
    sessionId: 's1',
  })
  const childB = await gw.complete({
    messages: [{ role: 'user', content: 'sub-task b' }],
    agentId: 'worker-b',
    parentTraceId: root.traceId,
    sessionId: 's1',
  })
  return [root.traceId, childA.traceId, childB.traceId]
}

interface DagResponse {
  nodes: Array<{
    traceId: string
    agentId: string | null
    subtree: { traces: number; errors: number }
  }>
  edges: Array<{ from: string; to: string }>
}

// ---- F-OBS-10: DAG + sessions -----------------------------------------------

describe('GET /api/dag', () => {
  it('builds the call graph with subtree rollups', async () => {
    const { gw, base } = await serve()
    const [rootId, childA, childB] = await runFamily(gw)

    const dag = (await getJson(base, `/api/dag?root=${rootId}`)) as unknown as DagResponse
    expect(new Set(dag.nodes.map((n) => n.traceId))).toEqual(new Set([rootId, childA, childB]))
    expect(new Set(dag.edges.map((e) => `${e.from}>${e.to}`))).toEqual(
      new Set([`${rootId}>${childA}`, `${rootId}>${childB}`]),
    )
    const rootNode = dag.nodes.find((n) => n.traceId === rootId)!
    expect(rootNode.agentId).toBe('orchestrator')
    expect(rootNode.subtree.traces).toBe(3)
    expect(rootNode.subtree.errors).toBe(0)
    const leaf = dag.nodes.find((n) => n.traceId === childA)!
    expect(leaf.subtree.traces).toBe(1)

    const bySession = (await getJson(base, '/api/dag?session_id=s1')) as unknown as DagResponse
    expect(bySession.nodes).toHaveLength(3)

    expect((await fetch(`${base}/api/dag`)).status).toBe(400)
    expect((await fetch(`${base}/api/dag?root=no-such-trace`)).status).toBe(404)
  })
})

describe('GET /api/sessions', () => {
  it('aggregates traces by session', async () => {
    const { gw, base } = await serve()
    await runFamily(gw)
    await gw.complete({ messages: [{ role: 'user', content: 'no session' }] })

    const sessions = (await getJson(base, '/api/sessions'))['sessions'] as Array<
      Record<string, unknown>
    >
    expect(sessions).toHaveLength(1)
    const s1 = sessions[0]!
    expect(s1['sessionId']).toBe('s1')
    expect(s1['traces']).toBe(3)
    expect(s1['errors']).toBe(0)
    expect([...(s1['agents'] as string[])].sort()).toEqual([
      'orchestrator',
      'worker-a',
      'worker-b',
    ])
    expect(s1['firstWallTimeUtc'] as string <= (s1['lastWallTimeUtc'] as string)).toBe(true)
  })
})

// ---- F-DX-08: stats ------------------------------------------------------------

describe('GET /api/stats', () => {
  it('serves RED aggregates and grouping', async () => {
    const { gw, base } = await serve()
    await runFamily(gw)

    const stats = await getJson(base, '/api/stats')
    const total = stats['total'] as Record<string, any>
    expect(total['requests']).toBe(3)
    expect(total['errors']).toBe(0)
    expect(total['errorRate']).toBe(0)
    expect(total['latencyMs']['p50']).not.toBeNull()
    expect(total['tokens']['total']).toBeGreaterThan(0)
    expect(total['cacheHitRate']).toBe(0)

    const grouped = await getJson(base, '/api/stats?group_by=agent_id')
    const groups = grouped['groups'] as Record<string, Record<string, unknown>>
    expect(new Set(Object.keys(groups))).toEqual(
      new Set(['orchestrator', 'worker-a', 'worker-b']),
    )
    expect(groups['worker-a']!['requests']).toBe(1)

    expect((await fetch(`${base}/api/stats?group_by=nope`)).status).toBe(400)
  })

  it('counts PII and errors from summaries (unit)', () => {
    const summaries = [
      { traceId: 'a', status: 'ok', latencyMs: 10, piiEntityTypes: ['EMAIL'] },
      { traceId: 'b', status: 'error', latencyMs: 30, piiEntityTypes: ['EMAIL', 'IBAN'] },
    ]
    const total = buildStats(summaries).total
    expect(total.errors).toBe(1)
    expect(total.errorRate).toBe(0.5)
    expect(total.piiDetections).toEqual({ EMAIL: 2, IBAN: 1 })
    expect(total.latencyMs.p50).toBe(10)
    expect(total.latencyMs.p99).toBe(30)
  })
})

// ---- F-DX-11: replay -------------------------------------------------------------

describe('POST /api/replay', () => {
  it('re-fires the trace and returns a new trace id', async () => {
    const { gw, base } = await serve()
    const response = await gw.complete({ messages: [{ role: 'user', content: 'replay me' }] })

    const replayedRes = await post(base, '/api/replay', { traceId: response.traceId })
    expect(replayedRes.status).toBe(200)
    const replayed = (await replayedRes.json()) as Record<string, string>
    expect(replayed['replayOf']).toBe(response.traceId)
    expect(replayed['traceId']).not.toBe(response.traceId)
    // The replayed call went through the live pipeline into the buffer.
    const newTrace = await getJson(base, `/api/traces/${replayed['traceId']}`)
    expect((newTrace['summary'] as Record<string, unknown>)['status']).toBe('ok')

    const editedRes = await post(base, '/api/replay', {
      traceId: response.traceId,
      overrides: { messages: [{ role: 'user', content: 'edited' }] },
    })
    expect(editedRes.status).toBe(200)
    const edited = (await editedRes.json()) as Record<string, string>
    const editedTrace = await getJson(base, `/api/traces/${edited['traceId']}`)
    const events = editedTrace['events'] as Array<{ type: string; data: Record<string, any> }>
    const start = events.find((e) => e.type === 'trace.start')!
    expect(start.data['messages'][0]['content']).toBe('edited')

    expect((await post(base, '/api/replay', { traceId: 'no-such-trace' })).status).toBe(404)
    expect((await post(base, '/api/replay', {})).status).toBe(400)
  })

  it('is 403 outside full mode', async () => {
    const { gw, base } = await serve('redacted')
    const response = await gw.complete({ messages: [{ role: 'user', content: 'hi' }] })
    expect((await post(base, '/api/replay', { traceId: response.traceId })).status).toBe(403)
  })
})

// ---- cost simulator ---------------------------------------------------------------

describe('GET /api/simulate-cost', () => {
  it('re-costs a trace under another model', async () => {
    const { gw, base } = await serve()
    const response = await gw.complete({
      messages: [{ role: 'user', content: 'price this call' }],
    })

    const simulated = (await getJson(
      base,
      `/api/simulate-cost?trace_id=${response.traceId}&model=gpt-4o`,
    )) as Record<string, any>
    expect(simulated['traceId']).toBe(response.traceId)
    expect(simulated['simulatedModel']).toBe('gpt-4o')
    expect(simulated['simulatedCostUsd']).toBeGreaterThan(0) // mock is free, gpt-4o is not
    expect(simulated['deltaUsd']).toBeCloseTo(
      simulated['simulatedCostUsd'] - simulated['costUsd'],
      8,
    )
    expect(simulated['usage']['totalTokens']).toBeGreaterThan(0)

    expect((await fetch(`${base}/api/simulate-cost?trace_id=missing-both`)).status).toBe(400)
  })
})

// ---- F-DX-12: export -----------------------------------------------------------------

describe('GET /api/traces/{id}/export', () => {
  it('sanitizes PII in the test-vector and testkit source', async () => {
    const gw = new Gateway({
      model: 'mock',
      inspect: { mode: 'full', port: 0, unsafeContentCaptureAck: true },
    })
      .withAdapter(mockProvider())
      .use(piiGuard())
    gateways.push(gw)
    const port = await gw.inspector!.ready()
    const base = `http://127.0.0.1:${port}`
    const response = await gw.complete({
      messages: [{ role: 'user', content: 'mail bob.real@corp.com about the invoice' }],
    })

    const vectorRes = await fetch(
      `${base}/api/traces/${response.traceId}/export?format=test-vector`,
    )
    expect(vectorRes.status).toBe(200)
    expect(vectorRes.headers.get('content-type')).toContain('application/json')
    const testCase = (await vectorRes.json()) as Record<string, any>
    expect(testCase['id']).toMatch(/^exported-/)
    expect(testCase['mode']).toBe('full')
    expect(testCase['interceptors']).toContain('pii_guard')
    const content = testCase['request']['messages'][0]['content'] as string
    expect(content).not.toContain('bob.real@corp.com')
    expect(content).toContain('jan@example.com')
    const expected = testCase['expectedEvents'] as Array<{ type: string }>
    expect(expected[0]!.type).toBe('trace.start')
    expect(expected[expected.length - 1]!.type).toBe('trace.end')

    const sourceRes = await fetch(
      `${base}/api/traces/${response.traceId}/export?format=testkit-py`,
    )
    expect(sourceRes.status).toBe(200)
    const source = await sourceRes.text()
    expect(source).toContain('GavioTestKit')
    expect(source).not.toContain('bob.real@corp.com')

    expect(
      (await fetch(`${base}/api/traces/${response.traceId}/export?format=nope`)).status,
    ).toBe(400)
  })

  it('is 403 in metadata mode', async () => {
    const { gw, base } = await serve('metadata')
    const response = await gw.complete({ messages: [{ role: 'user', content: 'hi' }] })
    expect(
      (await fetch(`${base}/api/traces/${response.traceId}/export?format=test-vector`)).status,
    ).toBe(403)
  })
})

// ---- F-DX-08: hash-chain verify gate + trace search -----------------------------------

describe('GET /api/chain/verify', () => {
  it('is 400 on the live server', async () => {
    const { base } = await serve()
    const response = await fetch(`${base}/api/chain/verify`)
    expect(response.status).toBe(400)
  })
})

describe('GET /api/traces?q=', () => {
  it('filters summaries by trace id prefix', async () => {
    const { gw, base } = await serve()
    const [rootId] = await runFamily(gw)

    const hits = (await getJson(base, `/api/traces?q=${rootId}`))['traces'] as Array<
      Record<string, unknown>
    >
    expect(hits.map((t) => t['traceId'])).toEqual([rootId])
    const misses = (await getJson(base, '/api/traces?q=zzzz'))['traces'] as unknown[]
    expect(misses).toHaveLength(0)
  })
})

// ---- analytics unit coverage ------------------------------------------------------

describe('analytics', () => {
  it('buildDag tolerates parent cycles', () => {
    const summaries = [
      { traceId: 'a', parentTraceId: 'b', status: 'ok', latencyMs: 1, costUsd: 0 },
      { traceId: 'b', parentTraceId: 'a', status: 'ok', latencyMs: 1, costUsd: 0 },
    ]
    const dag = buildDag(summaries, 'a')!
    expect(new Set(dag.nodes.map((n) => n.traceId))).toEqual(new Set(['a', 'b']))
  })

  it('buildSessions skips sessionless traces', () => {
    expect(buildSessions([{ traceId: 'a', sessionId: null }])).toEqual([])
  })
})
