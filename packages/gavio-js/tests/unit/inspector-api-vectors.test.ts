/**
 * Runs the shared v0.7.0 Inspector API cases from
 * //test-vectors/inspector/api-cases.json against the JS SDK — DAG assembly
 * (F-OBS-10) and replay-mode gating (F-DX-11). Same file the Python and Java
 * suites consume.
 */

import { readFileSync } from 'node:fs'
import { describe, it, expect, afterEach } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { buildDag } from '../../src/inspector/analytics.js'
import type { SummaryLike } from '../../src/inspector/analytics.js'
import type { InspectorMode } from '../../src/inspector/index.js'
import { mockProvider } from '../../src/providers/mock.js'

interface DagCase {
  id: string
  summaries: SummaryLike[]
  root?: string
  sessionId?: string
  expected: {
    nodes: number
    edges: number
    rootSubtree: { traces: number; errors: number; costUsd: number; latencyMs: number }
  }
}

interface ReplayGatingCase {
  mode: InspectorMode
  expectedStatus: number
}

const vectors = JSON.parse(
  readFileSync(
    new URL('../../../../test-vectors/inspector/api-cases.json', import.meta.url),
    'utf-8',
  ),
) as { dagCases: DagCase[]; replayGating: ReplayGatingCase[] }

describe('shared test-vectors — inspector/api-cases.json dagCases', () => {
  for (const dagCase of vectors.dagCases) {
    it(dagCase.id, () => {
      const dag = buildDag(dagCase.summaries, dagCase.root, dagCase.sessionId)
      expect(dag).not.toBeNull()
      expect(dag!.nodes).toHaveLength(dagCase.expected.nodes)
      expect(dag!.edges).toHaveLength(dagCase.expected.edges)
      const rootId =
        dagCase.root ?? dag!.nodes.find((n) => n.parentTraceId === null)!.traceId
      const rootNode = dag!.nodes.find((n) => n.traceId === rootId)!
      const expected = dagCase.expected.rootSubtree
      expect(rootNode.subtree.traces).toBe(expected.traces)
      expect(rootNode.subtree.errors).toBe(expected.errors)
      expect(rootNode.subtree.costUsd).toBeCloseTo(expected.costUsd, 8)
      expect(rootNode.subtree.latencyMs).toBe(expected.latencyMs)
    })
  }
})

describe('shared test-vectors — inspector/api-cases.json replayGating', () => {
  const gateways: Gateway[] = []

  afterEach(async () => {
    for (const gw of gateways.splice(0)) await gw.inspector?.close()
  })

  for (const gating of vectors.replayGating) {
    it(`mode=${gating.mode} → ${gating.expectedStatus}`, async () => {
      const gw = new Gateway({
        model: 'mock',
        inspect: { mode: gating.mode, port: 0, unsafeContentCaptureAck: true },
      }).withAdapter(mockProvider())
      gateways.push(gw)
      const port = await gw.inspector!.ready()
      const base = `http://127.0.0.1:${port}`

      const original = await gw.complete({ messages: [{ role: 'user', content: 'gate me' }] })
      const response = await fetch(`${base}/api/replay`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ traceId: original.traceId }),
      })
      expect(response.status).toBe(gating.expectedStatus)
      if (gating.expectedStatus === 200) {
        const body = (await response.json()) as { traceId: string; replayOf: string }
        expect(body.traceId).toBeTruthy()
        expect(body.replayOf).toBe(original.traceId)
      }
    })
  }
})
