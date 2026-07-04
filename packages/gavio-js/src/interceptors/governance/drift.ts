/**
 * driftMonitor (F-GOV-07) — alert when a provider's response distribution shifts.
 *
 * A `DriftDetector` is fed one metric sample per request (latency, tokens, …);
 * the default `StatisticalDriftDetector` keeps a rolling-window baseline and
 * flags a sample that deviates beyond a z-score threshold. Alerts surface as
 * `governance.event` inspector events (and in `/api/stats`) and are logged.
 */

import type { InterceptorContext } from '../../context.js'
import type { GavioResponse } from '../../response.js'
import type { Interceptor } from '../base.js'

export interface DriftBaseline {
  mean: number
  std: number
  n: number
}

export interface DriftAlert {
  metric: string
  value: number
  baseline: DriftBaseline
  /** Standard scores from the baseline mean; null when the baseline had zero variance. */
  z: number | null
  threshold: number
}

/** Pluggable drift detector: fed per-request samples, returns any alerts. */
export interface DriftDetector {
  readonly name: string
  observe(sample: Record<string, number>): DriftAlert[]
}

function round(value: number, decimals = 4): number {
  const factor = 10 ** decimals
  return Math.round(value * factor) / factor
}

function meanStd(values: number[]): { mean: number; std: number } {
  const n = values.length
  const mean = values.reduce((a, b) => a + b, 0) / n
  const variance = values.reduce((a, b) => a + (b - mean) ** 2, 0) / n
  return { mean, std: Math.sqrt(variance) }
}

export interface StatisticalDriftDetectorOptions {
  /** Rolling baseline window size (default 50). */
  windowSize?: number
  /** Minimum samples before any alert fires (default = windowSize). */
  minSamples?: number
  /** z-score sensitivity — deviation beyond this many std devs alerts (default 3). */
  threshold?: number
}

/** Rolling-window z-score detector — the default `DriftDetector`. */
export class StatisticalDriftDetector implements DriftDetector {
  readonly name = 'statistical'
  private readonly windowSize: number
  private readonly minSamples: number
  private readonly threshold: number
  private readonly windows = new Map<string, number[]>()

  constructor(options: StatisticalDriftDetectorOptions = {}) {
    this.windowSize = options.windowSize ?? 50
    this.minSamples = options.minSamples ?? this.windowSize
    this.threshold = options.threshold ?? 3
  }

  observe(sample: Record<string, number>): DriftAlert[] {
    const alerts: DriftAlert[] = []
    for (const [metric, value] of Object.entries(sample)) {
      if (!Number.isFinite(value)) continue
      let window = this.windows.get(metric)
      if (window === undefined) {
        window = []
        this.windows.set(metric, window)
      }
      if (window.length >= this.minSamples) {
        const { mean, std } = meanStd(window)
        const baseline: DriftBaseline = { mean: round(mean), std: round(std), n: window.length }
        if (std > 0) {
          const z = (value - mean) / std
          if (Math.abs(z) > this.threshold) {
            alerts.push({ metric, value, baseline, z: round(z), threshold: this.threshold })
          }
        } else if (value !== mean) {
          alerts.push({ metric, value, baseline, z: null, threshold: this.threshold })
        }
      }
      window.push(value)
      if (window.length > this.windowSize) window.shift()
    }
    return alerts
  }
}

export interface DriftMonitorOptions extends StatisticalDriftDetectorOptions {
  /** Metrics to watch (default `['latency_ms', 'total_tokens']`). */
  metrics?: string[]
  /** Override the detector (default a `StatisticalDriftDetector`). */
  detector?: DriftDetector
}

function extractSample(
  metrics: string[],
  response: GavioResponse,
  ctx: InterceptorContext,
): Record<string, number> {
  const sample: Record<string, number> = {}
  for (const metric of metrics) {
    if (metric === 'latency_ms') sample[metric] = response.latencyMs
    else if (metric === 'total_tokens') sample[metric] = response.usage.totalTokens
    else if (metric === 'cost_usd') sample[metric] = response.costUsd
    else if (metric === 'risk_score' && ctx.riskScore != null) sample[metric] = ctx.riskScore
  }
  return sample
}

/** Observe-only interceptor that flags response-distribution drift (F-GOV-07). */
export function driftMonitor(options: DriftMonitorOptions = {}): Interceptor {
  const metrics = options.metrics ?? ['latency_ms', 'total_tokens']
  const detector = options.detector ?? new StatisticalDriftDetector(options)
  return {
    name: 'drift_monitor',
    dryRunSafe: false, // never let a dry run pollute the baseline
    after(response: GavioResponse, ctx: InterceptorContext): GavioResponse {
      for (const alert of detector.observe(extractSample(metrics, response, ctx))) {
        ctx.recordGovernanceEvent({
          kind: 'drift',
          detector: detector.name,
          metric: alert.metric,
          value: alert.value,
          baseline: alert.baseline,
          z: alert.z,
          threshold: alert.threshold,
        })
        // eslint-disable-next-line no-console
        console.warn(
          `[gavio:drift] ${alert.metric}=${alert.value} drifted from baseline ` +
            `mean=${alert.baseline.mean} std=${alert.baseline.std} (n=${alert.baseline.n})`,
        )
      }
      return response
    },
  }
}
