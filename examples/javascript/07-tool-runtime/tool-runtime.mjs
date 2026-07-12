// Gavio Tool Runtime - validate tool results before model context reuse.

import { Gateway, ToolRuntimeError } from 'gavio'
import { analyzeToolRuntime, toolRuntime } from 'gavio/interceptors/tool-runtime'

const freshConflictTools = {
  now: '2026-07-12T12:00:30Z',
  conflict_keys: ['delivery_date'],
  calls: [
    {
      id: 'ship-a',
      name: 'shipping',
      source: 'carrier-a',
      created_at: '2026-07-12T12:00:00Z',
      confidence: 0.8,
      result: { delivery_date: 'Monday' },
    },
    {
      id: 'ship-b',
      name: 'shipping',
      source: 'carrier-b',
      created_at: '2026-07-12T12:00:00Z',
      confidence: 0.7,
      result: { delivery_date: 'Wednesday' },
    },
  ],
}

const staleTools = {
  now: '2026-07-12T12:03:00Z',
  max_age_seconds: 60,
  calls: [
    {
      id: 'price-1',
      name: 'price',
      source: 'pricing-cache',
      created_at: '2026-07-12T12:00:00Z',
      result: { sku: 'SKU-3', price: 9.99 },
      output_schema: {
        required: ['sku', 'price'],
        properties: { sku: 'string', price: 'number' },
      },
    },
  ],
}

const decision = analyzeToolRuntime(freshConflictTools)
console.log('conflicts :', decision.conflicts)
console.log('confidence:', decision.confidence)
console.log('sources   :', decision.provenance.map((p) => p.source))

const gw = new Gateway({ devMode: true }).use(toolRuntime({ onFailure: 'error' }))
try {
  await gw.complete({
    messages: [{ role: 'user', content: 'reuse the cached price quote' }],
    metadata: { tools: staleTools },
  })
} catch (err) {
  if (!(err instanceof ToolRuntimeError)) throw err
  console.log('blocked  :', err.message)
}
