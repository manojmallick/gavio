// Gavio Runtime Export - metadata-safe JSONL event export.

import { Gateway, jsonlRuntimeExporter } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const lines = []
const gateway = new Gateway({
  devMode: true,
  exporters: [jsonlRuntimeExporter({ write: (line) => lines.push(line) })],
}).use(piiGuard())

await gateway.complete({
  messages: [{ role: 'user', content: 'Email jan@example.com about ACME billing' }],
  metadata: { tenant: 'acme', feature: 'support-chat', environment: 'dev' },
})

const events = lines.map((line) => JSON.parse(line))
const contentKeys = ['messages', 'content', 'diff']
const leaked = events.filter((event) =>
  contentKeys.some((key) => JSON.stringify(event.data).includes(`"${key}"`)),
)

console.log(`exported_events=${events.length}`)
console.log(`event_types=${JSON.stringify(events.map((event) => event.type))}`)
console.log(`content_keys_exported=${leaked.length > 0}`)
