import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Gateway } from '../../src/gateway.js'
import { jsonlRuntimeExporter, metadataOnlyEvent } from '../../src/exporters/index.js'
import { piiGuard } from '../../src/interceptors/pii/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import type { InspectorEvent } from '../../src/inspector/index.js'

const vector = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../test-vectors/runtime-events/export-redaction.json', import.meta.url),
    ),
    'utf8',
  ),
) as {
  contentKeys: string[]
  event: InspectorEvent
  expectedData: Record<string, unknown>
}

describe('runtime exporters', () => {
  it('strips content-bearing fields from the shared vector', () => {
    const redacted = metadataOnlyEvent(vector.event)

    expect(redacted.data).toEqual(vector.expectedData)
    const serialized = JSON.stringify(redacted)
    for (const key of vector.contentKeys) {
      expect(serialized).not.toContain(`"${key}"`)
    }
  })

  it('auto-enables metadata events without starting the inspector server', async () => {
    const lines: string[] = []
    const gw = new Gateway({
      model: 'mock',
      exporters: [jsonlRuntimeExporter({ write: (line) => lines.push(line) })],
    }).withAdapter(mockProvider())

    expect(gw.inspector).not.toBeNull()
    expect(gw.inspector!.config.mode).toBe('metadata')
    expect(gw.inspector!.server).toBeNull()

    await gw.complete({ messages: [{ role: 'user', content: 'hello export' }] })

    const events = lines.map((line) => JSON.parse(line) as InspectorEvent)
    expect(events.map((event) => event.type)).toEqual([
      'trace.start',
      'provider.call.start',
      'provider.call.end',
      'trace.end',
    ])
    for (const event of events) {
      expect(event.data).not.toHaveProperty('messages')
      expect(event.data).not.toHaveProperty('content')
      expect(event.data).not.toHaveProperty('diff')
    }
  })

  it('strips content even when the local inspector is in full mode', async () => {
    const lines: string[] = []
    const gw = new Gateway({
      model: 'mock',
      inspect: { mode: 'full', startServer: false, unsafeContentCaptureAck: true },
      exporters: [jsonlRuntimeExporter({ write: (line) => lines.push(line) })],
    })
      .withAdapter(mockProvider())
      .use(piiGuard())

    const inspectorEvents: InspectorEvent[] = []
    gw.inspector!.bus.subscribe((event) => inspectorEvents.push(event))
    await gw.complete({ messages: [{ role: 'user', content: 'mail jan@example.com' }] })

    expect(inspectorEvents.some((event) => 'messages' in event.data)).toBe(true)
    const exported = lines.map((line) => JSON.parse(line) as InspectorEvent)
    expect(exported.length).toBeGreaterThan(0)
    for (const event of exported) {
      const serialized = JSON.stringify(event.data)
      expect(serialized).not.toContain('"messages"')
      expect(serialized).not.toContain('"content"')
      expect(serialized).not.toContain('"diff"')
    }
  })
})
