/**
 * Export a captured trace as a test case (F-DX-12).
 *
 * Renders a trace from the ring buffer as either a shared `test-vectors/`
 * JSON case or a GavioTestKit unit test (Python, Java, or TypeScript source).
 * Detected PII values are replaced with the repo's synthetic fixtures before
 * anything leaves the server, so real data never lands in a test file.
 *
 * The rendered template text is byte-identical across all three SDKs — keep
 * it in sync with the Python reference (`gavio/inspector/export.py`).
 */

import { ScanContext } from '../interceptors/pii/context.js'
import type { PiiMatch } from '../interceptors/pii/match.js'
import { defaultScanners } from '../interceptors/pii/scanners/index.js'
import type { SummaryLike } from './analytics.js'

export const EXPORT_FORMATS = ['test-vector', 'testkit-py', 'testkit-java', 'testkit-js'] as const

export type ExportFormat = (typeof EXPORT_FORMATS)[number]

/** Synthetic stand-ins per entity type — same fixtures used across test-vectors/. */
export const SYNTHETIC_FIXTURES: Record<string, string> = {
  EMAIL: 'jan@example.com',
  IBAN: 'NL91ABNA0417164300',
  BSN: '123456782',
  CREDIT_CARD: '4111111111111111',
  SSN: '078-05-1120',
  PHONE: '+31612345678',
  IP_ADDRESS: '192.0.2.1',
  SECRET: '***',
}

export interface ExportMessage {
  role: string
  content: string
}

/** Structural view of one assembled trace record ({ summary, events }). */
export interface ExportableTrace {
  summary: SummaryLike
  events: Array<{ type: string; data: Record<string, unknown> }>
}

const scanners = defaultScanners()

/** Replace every detected PII span with its synthetic fixture. */
export async function sanitizeText(text: string): Promise<string> {
  const matches: PiiMatch[] = []
  for (const scanner of scanners) {
    matches.push(...(await scanner.scan(text, new ScanContext())))
  }
  // Replace right-to-left so earlier offsets stay valid; skip overlaps.
  let lastStart = text.length + 1
  let out = text
  for (const m of [...matches].sort((a, b) => b.start - a.start)) {
    if (m.end > lastStart) continue
    const replacement = SYNTHETIC_FIXTURES[m.entityType] ?? '***'
    out = out.slice(0, m.start) + replacement + out.slice(m.end)
    lastStart = m.start
  }
  return out
}

export async function sanitizeMessages(
  messages: Array<{ role?: string; content?: string }>,
): Promise<ExportMessage[]> {
  const out: ExportMessage[] = []
  for (const m of messages) {
    out.push({ role: m.role ?? '', content: await sanitizeText(m.content ?? '') })
  }
  return out
}

/**
 * Render one assembled trace. Returns { contentType, body }.
 *
 * Throws an Error for an unknown format or a trace without captured messages
 * (metadata-mode traces cannot be exported).
 */
export async function exportTrace(
  trace: ExportableTrace,
  format: string,
): Promise<{ contentType: string; body: string }> {
  if (!(EXPORT_FORMATS as readonly string[]).includes(format)) {
    throw new Error(`format must be one of ${EXPORT_FORMATS.join(', ')}`)
  }
  const start = trace.events.find((e) => e.type === 'trace.start')
  const raw = start?.data['messages'] as Array<{ role?: string; content?: string }> | undefined
  if (raw === undefined || raw.length === 0) {
    throw new Error('trace has no captured messages (metadata mode traces cannot be exported)')
  }
  const messages = await sanitizeMessages(raw)
  const summary = trace.summary
  if (format === 'test-vector') {
    return {
      contentType: 'application/json',
      body: ensureAscii(JSON.stringify(testVector(summary, trace.events, messages), null, 2)),
    }
  }
  return {
    contentType: 'text/plain; charset=utf-8',
    body: testkit(format as ExportFormat, summary, messages),
  }
}

function testVector(
  summary: SummaryLike,
  events: Array<{ type: string; data: Record<string, unknown> }>,
  messages: ExportMessage[],
): Record<string, unknown> {
  const expected: Array<Record<string, unknown>> = []
  for (const event of events) {
    const entry: Record<string, unknown> = { type: event.type }
    const data = event.data
    if (event.type.startsWith('interceptor.') && data['name']) entry['name'] = data['name']
    if ('status' in data && (event.type === 'provider.call.end' || event.type === 'trace.end')) {
      entry['status'] = data['status']
    }
    expected.push(entry)
  }
  const startWithMode = events.find((e) => e.type === 'trace.start' && 'mode' in e.data)
  const mode = startWithMode !== undefined ? startWithMode.data['mode'] : 'full'
  const interceptors = (summary.interceptorsFired ?? []).filter((name) => !name.startsWith('_'))
  return {
    id: `exported-${summary.traceId.slice(0, 8)}`,
    mode,
    interceptors,
    request: { messages },
    expectedEvents: expected,
  }
}

function testkit(format: ExportFormat, summary: SummaryLike, messages: ExportMessage[]): string {
  const fired = (summary.interceptorsFired ?? []).filter((n) => !n.startsWith('_'))
  const pii = fired.includes('pii_guard')
  const audit = fired.includes('audit')
  const other = fired.filter((n) => n !== 'pii_guard' && n !== 'audit')
  const traceId = summary.traceId
  const slug = traceId.slice(0, 8).replace(/-/g, '')
  if (format === 'testkit-py') {
    const uses: string[] = []
    if (pii) uses.push('PiiGuard()')
    if (audit) uses.push('AuditInterceptor()')
    let imports = ''
    if (pii) imports += 'from gavio.interceptors.pii import PiiGuard\n'
    if (audit) imports += 'from gavio.interceptors.audit import AuditInterceptor\n'
    const note = other.length > 0
      ? `# also fired in the original trace: ${other.join(', ')}\n    `
      : ''
    const assertion = pii ? '\n    assert kit.pii_detected()' : ''
    return (
      `"""Exported from the Gavio Inspector — trace ${traceId}."""\n` +
      `from gavio.testing import GavioTestKit\n${imports}\n\n` +
      `async def test_exported_trace_${slug}() -> None:\n` +
      `    ${note}kit = GavioTestKit(interceptors=[${uses.join(', ')}])\n` +
      `    messages = ${pyJson(messages)}\n` +
      '    response = await kit.run(messages)\n' +
      `    assert response.content${assertion}\n`
    )
  }
  if (format === 'testkit-js') {
    const uses: string[] = []
    if (pii) uses.push('new PiiGuard()')
    if (audit) uses.push('new AuditInterceptor()')
    let imports = "import { GavioTestKit } from 'gavio/testing'\n"
    if (pii) imports += "import { PiiGuard } from 'gavio/interceptors/pii'\n"
    if (audit) imports += "import { AuditInterceptor } from 'gavio/interceptors/audit'\n"
    const noteJs = other.length > 0
      ? `// also fired in the original trace: ${other.join(', ')}\n  `
      : ''
    const assertion = pii ? '\n  expect(result.piiDetected()).toBe(true)' : ''
    return (
      `// Exported from the Gavio Inspector — trace ${traceId}\n` +
      `import { expect, test } from 'vitest'\n${imports}\n` +
      `test('exported trace ${slug}', async () => {\n` +
      `  ${noteJs}const kit = new GavioTestKit({ interceptors: [${uses.join(', ')}] })\n` +
      `  const messages = ${pyJson(messages)}\n` +
      '  const result = await kit.run({ messages })\n' +
      `  expect(result.response.content).toBeTruthy()${assertion}\n` +
      '})\n'
    )
  }
  // testkit-java
  const javaUses = [
    ...(pii ? ['new PiiGuard()'] : []),
    ...(audit ? ['new AuditInterceptor()'] : []),
  ]
  const builderUses = javaUses.map((u) => `.interceptor(${u})`).join('')
  const noteJava = other.length > 0
    ? `// also fired in the original trace: ${other.join(', ')}\n        `
    : ''
  const javaMessages = messages
    .map((m) => `Message.of(${pyStr(m.role)}, ${pyStr(m.content)})`)
    .join(',\n                ')
  const assertion = pii ? '\n        assertTrue(result.piiDetected(null));' : ''
  return (
    `// Exported from the Gavio Inspector — trace ${traceId}\n` +
    'import static org.junit.jupiter.api.Assertions.*;\n\n' +
    'import io.gavio.testing.GavioTestKit;\n' +
    'import io.gavio.testing.GavioTestResult;\n' +
    'import io.gavio.types.Message;\n' +
    'import java.util.List;\n' +
    'import org.junit.jupiter.api.Test;\n\n' +
    `class ExportedTrace${slug}Test {\n\n` +
    '    @Test\n' +
    '    void exportedTrace() {\n' +
    `        ${noteJava}GavioTestKit kit = GavioTestKit.builder()${builderUses}.build();\n` +
    `        GavioTestResult result = kit.run(List.of(\n                ${javaMessages})).join();\n` +
    `        assertNotNull(result.response().content());${assertion}\n` +
    '    }\n' +
    '}\n'
  )
}

/** Escape non-ASCII like Python's `json.dumps(ensure_ascii=True)`. */
function ensureAscii(json: string): string {
  return json.replace(
    /[\u007f-\uffff]/g,
    (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'),
  )
}

/** Python-`json.dumps`-compatible string literal (ensure_ascii escaping). */
function pyStr(value: string): string {
  return ensureAscii(JSON.stringify(value))
}

/** Python-`json.dumps`-compatible compact serialization (", " / ": " separators). */
function pyJson(value: unknown): string {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'string') return pyStr(value)
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'number') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(pyJson).join(', ')}]`
  const entries = Object.entries(value as Record<string, unknown>)
  return `{${entries.map(([k, v]) => `${pyStr(k)}: ${pyJson(v)}`).join(', ')}}`
}
