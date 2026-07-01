/** metricsInterceptor (F-OBS-08) — records Prometheus metrics per request. */

import type { InterceptorContext } from '../../context.js'
import type { GavioResponse } from '../../response.js'
import type { Interceptor } from '../base.js'
import { PrometheusMetrics } from './registry.js'

export const METRICS_NAME = 'metrics'

/** An interceptor that also exposes the registry it records into. */
export interface MetricsInterceptor extends Interceptor {
  readonly metrics: PrometheusMetrics
}

/**
 * Build a metrics interceptor. Pass a shared {@link PrometheusMetrics} registry
 * (or let it create one) and scrape it via `.metrics.render()`:
 *
 * ```ts
 * const m = metricsInterceptor()
 * const gw = new Gateway({ devMode: true }).use(m)
 * // ...
 * console.log(m.metrics.render())
 * ```
 *
 * Observation-only, so it always runs (including in dry-run).
 */
export function metricsInterceptor(
  metrics: PrometheusMetrics = new PrometheusMetrics(),
): MetricsInterceptor {
  return {
    name: METRICS_NAME,
    dryRunSafe: true,
    metrics,
    async after(response: GavioResponse, _ctx: InterceptorContext): Promise<GavioResponse> {
      metrics.record(response.provider, response.model, {
        promptTokens: response.usage.promptTokens,
        completionTokens: response.usage.completionTokens,
        costUsd: response.costUsd,
        latencyMs: response.latencyMs,
        cacheHit: response.cacheHit,
      })
      return response
    },
  }
}
