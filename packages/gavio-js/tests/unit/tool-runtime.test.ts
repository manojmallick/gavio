import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { ToolRuntimeError } from '../../src/errors.js'
import type { InterceptorContext } from '../../src/context.js'
import type { Interceptor } from '../../src/interceptors/base.js'
import { analyzeToolRuntime, toolRuntime } from '../../src/interceptors/tool-runtime/index.js'

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
  }
}

function vectorCases(): VectorCase[] {
  const text = readFileSync(join(process.cwd(), '../../test-vectors/tool-runtime/cases.json'), 'utf8')
  return JSON.parse(text).cases as VectorCase[]
}

describe('Tool Runtime shared vectors', () => {
  for (const c of vectorCases()) {
    it(c.id, () => {
      const decision = analyzeToolRuntime(c.tools)
      expect(decision.violations).toHaveLength(c.expected.violation_count)
      expect(decision.conflicts).toHaveLength(c.expected.conflict_count ?? 0)
      if (c.expected.confidence !== undefined) expect(decision.confidence).toBeCloseTo(c.expected.confidence)
      if (c.expected.provenance_count !== undefined) {
        expect(decision.provenance).toHaveLength(c.expected.provenance_count)
      }
      if (c.expected.first_violation_kind !== undefined) {
        expect(decision.violations[0]!['kind']).toBe(c.expected.first_violation_kind)
      }
      if (c.expected.first_conflict_key !== undefined) {
        expect(decision.conflicts[0]!['key']).toBe(c.expected.first_conflict_key)
      }
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
