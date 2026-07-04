import { describe, it, expect, vi } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import {
  driftMonitor,
  StatisticalDriftDetector,
  type DriftDetector,
} from '../../src/interceptors/governance/index.js'
import { buildStats } from '../../src/inspector/index.js'
import type { InspectorEvent } from '../../src/inspector/index.js'
import { mockProvider } from '../../src/providers/mock.js'

describe('StatisticalDriftDetector (F-GOV-07)', () => {
  it('stays silent while the baseline window fills', () => {
    const d = new StatisticalDriftDetector({ windowSize: 10, minSamples: 10, threshold: 3 })
    for (let i = 0; i < 9; i++) expect(d.observe({ latency_ms: 100 + i })).toEqual([])
  })

  it('flags a value that deviates beyond the z-score threshold', () => {
    const d = new StatisticalDriftDetector({ windowSize: 20, minSamples: 12, threshold: 3 })
    // Baseline: latencies jittering around ~100ms.
    for (let i = 0; i < 12; i++) d.observe({ latency_ms: 100 + (i % 5) })
    expect(d.observe({ latency_ms: 105 })).toEqual([]) // in-distribution → quiet
    const alerts = d.observe({ latency_ms: 900 }) // spike → drift
    expect(alerts).toHaveLength(1)
    expect(alerts[0]!.metric).toBe('latency_ms')
    expect(alerts[0]!.value).toBe(900)
    expect(alerts[0]!.baseline.n).toBeGreaterThanOrEqual(12)
    expect(Math.abs(alerts[0]!.z as number)).toBeGreaterThan(3)
  })

  it('treats any deviation from a zero-variance baseline as drift (z=null)', () => {
    const d = new StatisticalDriftDetector({ windowSize: 10, minSamples: 5, threshold: 3 })
    for (let i = 0; i < 5; i++) d.observe({ total_tokens: 42 })
    const alerts = d.observe({ total_tokens: 43 })
    expect(alerts).toHaveLength(1)
    expect(alerts[0]!.z).toBeNull()
    expect(alerts[0]!.baseline.std).toBe(0)
  })

  it('tracks each metric independently', () => {
    const d = new StatisticalDriftDetector({ windowSize: 10, minSamples: 4, threshold: 3 })
    for (let i = 0; i < 4; i++) d.observe({ latency_ms: 100 + (i % 3), total_tokens: 50 + (i % 3) })
    const alerts = d.observe({ latency_ms: 100, total_tokens: 5000 })
    expect(alerts.map((a) => a.metric)).toEqual(['total_tokens'])
  })
})

describe('driftMonitor interceptor — governance.event + /api/stats', () => {
  // Deterministic detector so the integration path is exercised without relying
  // on mock latency: alert on the first metric of every request.
  const alwaysDrift: DriftDetector = {
    name: 'stub',
    observe(sample) {
      const [metric, value] = Object.entries(sample)[0]!
      return [{ metric, value, baseline: { mean: 100, std: 10, n: 20 }, z: 80, threshold: 3 }]
    },
  }

  it('emits a governance.event and surfaces the drift in /api/stats', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const gw = new Gateway({ model: 'mock', inspect: { mode: 'metadata', startServer: false } })
      .withAdapter(mockProvider({ response: 'ok' }))
      .use(driftMonitor({ detector: alwaysDrift, metrics: ['latency_ms'] }))

    const events: InspectorEvent[] = []
    gw.inspector!.bus.subscribe((e) => events.push(e))

    await gw.complete({ messages: [{ role: 'user', content: 'a' }] })
    await gw.complete({ messages: [{ role: 'user', content: 'b' }] })

    const governance = events.filter((e) => e.type === 'governance.event')
    expect(governance).toHaveLength(2)
    expect(governance[0]!.data['kind']).toBe('drift')
    expect(governance[0]!.data['metric']).toBe('latency_ms')

    // /api/stats aggregation over the inspector buffer.
    const stats = buildStats(gw.inspector!.buffer.list())
    expect(stats.total.driftAlerts).toEqual({ latency_ms: 2 })
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })

  it('emits nothing when the detector reports no drift', async () => {
    const gw = new Gateway({ model: 'mock', inspect: { mode: 'metadata', startServer: false } })
      .withAdapter(mockProvider({ response: 'ok' }))
      .use(driftMonitor({ detector: { name: 'quiet', observe: () => [] } }))
    const events: InspectorEvent[] = []
    gw.inspector!.bus.subscribe((e) => events.push(e))

    await gw.complete({ messages: [{ role: 'user', content: 'a' }] })

    expect(events.filter((e) => e.type === 'governance.event')).toHaveLength(0)
    expect(buildStats(gw.inspector!.buffer.list()).total.driftAlerts).toEqual({})
  })
})
