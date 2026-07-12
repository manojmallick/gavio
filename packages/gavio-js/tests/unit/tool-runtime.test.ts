import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { ToolRuntimeError } from '../../src/errors.js'
import type { InterceptorContext } from '../../src/context.js'
import type { Interceptor } from '../../src/interceptors/base.js'
import {
  analyzeToolRuntime,
  replayToolRuntime,
  toolRuntime,
} from '../../src/interceptors/tool-runtime/index.js'

interface VectorCase {
  id: string
  tools: Record<string, unknown>
  expected: {
    violation_count: number
    conflict_count?: number
    confidence?: number
    provenance_count?: number
    first_violation_kind?: string
    first_conflict_key?: string
    decision_count?: number
    first_action?: string
    first_approved?: boolean
    approval_required_count?: number
    blocked_count?: number
    first_mcp_server?: string
    replayable?: boolean
  }
  record?: Record<string, unknown>
}

function vectorCases(name = 'cases.json'): VectorCase[] {
  const text = readFileSync(join(process.cwd(), `../../test-vectors/tool-runtime/${name}`), 'utf8')
  return JSON.parse(text).cases as VectorCase[]
}

function assertDecision(
  decision: ReturnType<typeof analyzeToolRuntime>,
  expected: VectorCase['expected'],
): void {
  expect(decision.violations).toHaveLength(expected.violation_count)
  expect(decision.conflicts).toHaveLength(expected.conflict_count ?? 0)
  if (expected.confidence !== undefined) expect(decision.confidence).toBeCloseTo(expected.confidence)
  if (expected.provenance_count !== undefined) {
    expect(decision.provenance).toHaveLength(expected.provenance_count)
  }
  if (expected.first_violation_kind !== undefined) {
    expect(decision.violations[0]!['kind']).toBe(expected.first_violation_kind)
  }
  if (expected.first_conflict_key !== undefined) {
    expect(decision.conflicts[0]!['key']).toBe(expected.first_conflict_key)
  }
  if (expected.decision_count !== undefined) {
    expect(decision.decisions).toHaveLength(expected.decision_count)
  }
  if (expected.first_action !== undefined) {
    expect(decision.decisions[0]!['action']).toBe(expected.first_action)
  }
  if (expected.first_approved !== undefined) {
    expect(decision.decisions[0]!['approved']).toBe(expected.first_approved)
  }
  if (expected.approval_required_count !== undefined) {
    expect(decision.approvalsRequired).toBe(expected.approval_required_count)
  }
  if (expected.blocked_count !== undefined) expect(decision.blocked).toBe(expected.blocked_count)
  if (expected.first_mcp_server !== undefined) {
    expect(decision.provenance[0]!['mcp_server']).toBe(expected.first_mcp_server)
  }
  if (expected.replayable !== undefined) expect(decision.replayable).toBe(expected.replayable)
}

describe('Tool Runtime shared vectors', () => {
  for (const c of vectorCases()) {
    it(c.id, () => {
      const decision = analyzeToolRuntime(c.tools)
      assertDecision(decision, c.expected)
    })
  }
})

describe('Tool Runtime v2 permission vectors', () => {
  for (const c of vectorCases('permissions.json')) {
    it(c.id, () => {
      const decision = analyzeToolRuntime(c.tools)
      assertDecision(decision, c.expected)
    })
  }
})

describe('Tool Runtime v2 replay vectors', () => {
  for (const c of vectorCases('replay.json')) {
    it(c.id, () => {
      const decision = replayToolRuntime(c.record)
      assertDecision(decision, c.expected)
    })
  }
})

describe('Tool Runtime interceptor', () => {
  it('records runtime context without mutating request metadata', async () => {
    const tools = vectorCases()[0]!.tools
    let captured: InterceptorContext | null = null
    const capture: Interceptor = {
      name: 'tool_runtime_capture',
      before(request, ctx) {
        captured = ctx
        return request
      },
    }
    const gw = new Gateway({ devMode: true }).use(toolRuntime()).use(capture)

    await gw.complete({ messages: [{ role: 'user', content: 'hi' }], metadata: { tools } })

    expect(tools['runtime']).toBeUndefined()
    expect(captured).not.toBeNull()
    const runtime = captured!.tools['runtime'] as Record<string, unknown>
    expect(runtime['callCount']).toBe(1)
    expect(runtime['violations']).toEqual([])
    expect((runtime['provenance'] as Record<string, unknown>[])[0]!['source']).toBe('warehouse-a')
  })

  it('blocks invalid tool context when configured for error', async () => {
    const tools = vectorCases()[1]!.tools
    const gw = new Gateway({ devMode: true }).use(toolRuntime({ onFailure: 'error' }))

    await expect(
      gw.complete({ messages: [{ role: 'user', content: 'hi' }], metadata: { tools } }),
    ).rejects.toBeInstanceOf(ToolRuntimeError)
  })
})
