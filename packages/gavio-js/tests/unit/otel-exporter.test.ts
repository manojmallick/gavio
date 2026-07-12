import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { otelSpanExporter, otelSpansFromEvents, type OtelSpan } from '../../src/exporters/index.js'
import type { InspectorEvent } from '../../src/inspector/index.js'

const vector = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../test-vectors/otel/spans.json', import.meta.url)),
    'utf8',
  ),
) as {
  contentKeys: string[]
  cases: Array<{
    name: string
    serviceName: string
    events: InspectorEvent[]
    expected: Record<string, unknown>
  }>
}

describe('OTel span exporter', () => {
  it('matches the shared span vectors', () => {
    for (const testCase of vector.cases) {
      const spans = otelSpansFromEvents(testCase.events, { serviceName: testCase.serviceName })
      assertCase(testCase, spans)
      const serialized = JSON.stringify(spans)
      for (const key of vector.contentKeys) {
        expect(serialized).not.toContain(`"${key}"`)
      }
    }
  })

  it('writes OTel span JSONL', () => {
    const testCase = vector.cases[0]!
    const lines: string[] = []
    const exporter = otelSpanExporter({
      serviceName: testCase.serviceName,
      writeLine: (line) => lines.push(line),
    })

    for (const event of testCase.events) exporter.exportEvent(event)

    const spans = lines.map((line) => JSON.parse(line) as OtelSpan)
    assertCase(testCase, spans)
  })
})

function assertCase(testCase: (typeof vector.cases)[number], spans: OtelSpan[]): void {
  const expected = testCase.expected as unknown as ExpectedCase
  expect(spans.map((span) => span.name)).toEqual(expected.spanNames)

  const root = span(spans, expected.root.name)
  expect(root.parentSpanId).toBeNull()
  expect(root.status.code).toBe(expected.root.status)
  expect(root.endTimeUnixNano - root.startTimeUnixNano).toBe(expected.root.durationNs)
  assertAttrs(root, expected.root.attributes)
  if (expected.root.eventNames !== undefined) {
    expect(root.events.map((event) => event.name)).toEqual(expected.root.eventNames)
  }

  for (const section of ['provider', 'interceptor'] as const) {
    const sectionExpected = expected[section]
    if (sectionExpected === undefined) continue
    const child = span(spans, sectionExpected.name)
    expect(child.parentSpanId).toBe(root.spanId)
    expect(child.status.code).toBe(sectionExpected.status)
    expect(child.startTimeUnixNano - root.startTimeUnixNano).toBe(sectionExpected.startOffsetNs)
    expect(child.endTimeUnixNano - root.startTimeUnixNano).toBe(sectionExpected.endOffsetNs)
    assertAttrs(child, sectionExpected.attributes)
  }
}

function span(spans: OtelSpan[], name: string): OtelSpan {
  const found = spans.find((candidate) => candidate.name === name)
  expect(found).toBeDefined()
  return found!
}

function assertAttrs(span: OtelSpan, expected: Record<string, unknown>): void {
  for (const [key, value] of Object.entries(expected)) {
    expect(span.attributes[key]).toEqual(value)
  }
}

interface ExpectedCase {
  spanNames: string[]
  root: ExpectedSpan & { durationNs: number; eventNames?: string[] }
  provider?: ExpectedSpan & { startOffsetNs: number; endOffsetNs: number }
  interceptor?: ExpectedSpan & { startOffsetNs: number; endOffsetNs: number }
}

interface ExpectedSpan {
  name: string
  status: string
  attributes: Record<string, unknown>
}
