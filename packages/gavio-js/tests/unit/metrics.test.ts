import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { PrometheusMetrics, metricsInterceptor } from '../../src/interceptors/metrics/index.js'

describe('Prometheus metrics (F-OBS-08)', () => {
  it('renders counters and a histogram', () => {
    const m = new PrometheusMetrics()
    m.record('openai', 'gpt-4o', { promptTokens: 10, completionTokens: 5, costUsd: 0.002, latencyMs: 42 })
    m.record('openai', 'gpt-4o', { promptTokens: 20, completionTokens: 8, costUsd: 0.004, latencyMs: 8 })
    const text = m.render()

    expect(text).toContain('gavio_requests_total{provider="openai",model="gpt-4o"} 2')
    expect(text).toContain('gavio_tokens_total{provider="openai",model="gpt-4o",kind="prompt"} 30')
    expect(text).toContain('gavio_tokens_total{provider="openai",model="gpt-4o",kind="completion"} 13')
    // Histogram: le="10" has 1 (the 8ms obs), +Inf has 2, count 2, sum 50.
    expect(text).toContain('gavio_request_latency_ms_bucket{provider="openai",model="gpt-4o",le="10"} 1')
    expect(text).toContain('gavio_request_latency_ms_bucket{provider="openai",model="gpt-4o",le="+Inf"} 2')
    expect(text).toContain('gavio_request_latency_ms_count{provider="openai",model="gpt-4o"} 2')
    expect(text).toContain('gavio_request_latency_ms_sum{provider="openai",model="gpt-4o"} 50')
  })

  it('emits HELP + TYPE lines for every metric and a trailing newline', () => {
    const text = new PrometheusMetrics().render()
    for (const metric of [
      'gavio_requests_total',
      'gavio_tokens_total',
      'gavio_cost_usd_total',
      'gavio_request_latency_ms',
      'gavio_cache_hits_total',
    ]) {
      expect(text).toContain(`# HELP ${metric}`)
      expect(text).toContain(`# TYPE ${metric}`)
    }
    expect(text.endsWith('\n')).toBe(true)
  })

  it('counts cache hits', () => {
    const m = new PrometheusMetrics()
    m.record('mock', 'mock', { cacheHit: true })
    m.record('mock', 'mock', { cacheHit: false })
    expect(m.render()).toContain('gavio_cache_hits_total{provider="mock",model="mock"} 1')
  })

  it('separates series by provider and model', () => {
    const m = new PrometheusMetrics()
    m.record('openai', 'gpt-4o')
    m.record('anthropic', 'claude-sonnet-4-6')
    const text = m.render()
    expect(text).toContain('gavio_requests_total{provider="openai",model="gpt-4o"} 1')
    expect(text).toContain('gavio_requests_total{provider="anthropic",model="claude-sonnet-4-6"} 1')
  })

  it('records from the gateway via the interceptor', async () => {
    const mi = metricsInterceptor()
    const gw = new Gateway({ devMode: true }).use(mi)
    for (let i = 0; i < 3; i++) await gw.complete({ messages: [{ role: 'user', content: `m${i}` }] })

    const text = mi.metrics.render()
    expect(text).toContain('gavio_requests_total{provider="mock",model="mock"} 3')
    expect(text).toContain('gavio_request_latency_ms_count{provider="mock",model="mock"} 3')
  })
})
