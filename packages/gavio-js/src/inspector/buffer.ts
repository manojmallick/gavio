/**
 * TraceBuffer — a bounded ring buffer that subscribes to the InspectorBus and
 * assembles events into per-trace records: { summary, events[] }.
 *
 * Bounded on both axes: at most `maxTraces` traces (oldest evicted first) and
 * at most `maxEventsPerTrace` events kept per trace.
 */

import type { InspectorEvent } from './events.js'
import { COST_DIMENSION_KEYS, type CostDimensions } from './events.js'

/** Roll-up of one trace, fed by trace.start and trace.end events. */
export interface TraceSummary {
  traceId: string
  parentTraceId: string | null
  agentId: string | null
  sessionId: string | null
  provider: string | null
  model: string | null
  status: string
  latencyMs: number | null
  costUsd: number | null
  cacheHit: boolean | null
  cacheType: string | null
  piiEntityTypes: string[]
  wallTimeUtc: string | null
  interceptorsFired: string[]
  costDimensions: CostDimensions
  feature: string | null
  tenant: string | null
  user: string | null
  endpoint: string | null
  environment: string | null
  workflow: string | null
  tool: string | null
  middlewareChain: string | null
  providerCallCount: number
  retryCount: number
  retryOverheadUsd: number
  cacheSavingsUsd: number
  /** Token usage from provider.call.end — feeds /api/stats and /api/simulate-cost. */
  usage?: { promptTokens: number; completionTokens: number; totalTokens: number }
  /** Content hashes (audit-store summaries) — searchable via /api/traces?q=. */
  promptHash?: string
  responseHash?: string
  /** Metrics that drifted on this trace (F-GOV-07), from governance.event events. */
  driftAlerts?: string[]
}

export interface TraceRecord {
  summary: TraceSummary
  events: InspectorEvent[]
}

export interface TraceBufferOptions {
  maxTraces?: number
  maxEventsPerTrace?: number
}

export const DEFAULT_MAX_EVENTS_PER_TRACE = 500

export class TraceBuffer {
  private readonly maxTraces: number
  private readonly maxEventsPerTrace: number
  /** Insertion-ordered (Map preserves insertion order) — oldest trace first. */
  private readonly traces = new Map<string, TraceRecord>()

  constructor(options: TraceBufferOptions = {}) {
    this.maxTraces = options.maxTraces ?? 1000
    this.maxEventsPerTrace = options.maxEventsPerTrace ?? DEFAULT_MAX_EVENTS_PER_TRACE
  }

  /** Bus subscriber — bind or wrap with an arrow when subscribing. */
  handle(event: InspectorEvent): void {
    const record = this.recordFor(event.traceId)
    if (record.events.length < this.maxEventsPerTrace) {
      record.events.push(event)
    }
    if (event.type === 'trace.start') this.applyStart(record.summary, event.data)
    if (event.type === 'trace.end') this.applyEnd(record.summary, event.data)
    if (event.type === 'provider.call.start') this.applyProviderCallStart(record.summary, event.data)
    if (event.type === 'provider.call.end' && event.data['usage'] !== undefined) {
      record.summary.usage = event.data['usage'] as TraceSummary['usage']
    }
    if (event.type === 'provider.call.end') {
      this.applyProviderCallEnd(record.summary, event.data)
    }
    if (event.type === 'governance.event' && event.data['kind'] === 'drift') {
      const metric = event.data['metric']
      if (typeof metric === 'string') {
        ;(record.summary.driftAlerts ??= []).push(metric)
      }
    }
  }

  /** Trace summaries in chronological (ascending) order; limit keeps the most recent N. */
  list(limit?: number): TraceSummary[] {
    const all = [...this.traces.values()].map((r) => r.summary)
    if (limit !== undefined && limit >= 0 && limit < all.length) {
      return all.slice(all.length - limit)
    }
    return all
  }

  get(traceId: string): TraceRecord | undefined {
    return this.traces.get(traceId)
  }

  get size(): number {
    return this.traces.size
  }

  private recordFor(traceId: string): TraceRecord {
    let record = this.traces.get(traceId)
    if (record !== undefined) return record
    record = { summary: emptySummary(traceId), events: [] }
    this.traces.set(traceId, record)
    while (this.traces.size > this.maxTraces) {
      const oldest = this.traces.keys().next().value as string
      this.traces.delete(oldest)
    }
    return record
  }

  private applyStart(summary: TraceSummary, data: Record<string, unknown>): void {
    summary.parentTraceId = (data['parentTraceId'] as string | null | undefined) ?? null
    summary.agentId = (data['agentId'] as string | null | undefined) ?? null
    summary.sessionId = (data['sessionId'] as string | null | undefined) ?? null
    summary.provider = (data['provider'] as string | undefined) ?? null
    summary.model = (data['model'] as string | undefined) ?? null
    summary.wallTimeUtc = (data['wallTimeUtc'] as string | undefined) ?? null
    summary.costDimensions = normalizeCostDimensions(data['costDimensions'])
    for (const key of COST_DIMENSION_KEYS) {
      ;(summary as unknown as Record<typeof key, string | null>)[key] =
        summary.costDimensions[key] ?? null
    }
  }

  private applyEnd(summary: TraceSummary, data: Record<string, unknown>): void {
    summary.status = (data['status'] as string | undefined) ?? summary.status
    summary.latencyMs = (data['latencyMs'] as number | undefined) ?? null
    summary.costUsd = (data['costUsd'] as number | undefined) ?? null
    summary.cacheHit = (data['cacheHit'] as boolean | undefined) ?? null
    summary.cacheType = (data['cacheType'] as string | null | undefined) ?? null
    summary.piiEntityTypes = (data['piiEntityTypes'] as string[] | undefined) ?? []
    summary.interceptorsFired = (data['interceptorsFired'] as string[] | undefined) ?? []
    summary.middlewareChain =
      summary.interceptorsFired.length > 0 ? summary.interceptorsFired.join('>') : null
    summary.cacheSavingsUsd = numberValue(data['cacheSavingsUsd']) ?? summary.cacheSavingsUsd
  }

  private applyProviderCallStart(summary: TraceSummary, data: Record<string, unknown>): void {
    const attempt = numberValue(data['attempt']) ?? summary.providerCallCount + 1
    summary.providerCallCount = Math.max(summary.providerCallCount, attempt)
    summary.retryCount = Math.max(0, summary.providerCallCount - 1)
  }

  private applyProviderCallEnd(summary: TraceSummary, data: Record<string, unknown>): void {
    const attempt = numberValue(data['attempt'])
    const costUsd = numberValue(data['costUsd']) ?? 0
    if (attempt !== undefined && attempt > 1 && costUsd > 0) {
      summary.retryOverheadUsd = round8(summary.retryOverheadUsd + costUsd)
    }
  }
}

function emptySummary(traceId: string): TraceSummary {
  return {
    traceId,
    parentTraceId: null,
    agentId: null,
    sessionId: null,
    provider: null,
    model: null,
    status: 'running',
    latencyMs: null,
    costUsd: null,
    cacheHit: null,
    cacheType: null,
    piiEntityTypes: [],
    wallTimeUtc: null,
    interceptorsFired: [],
    costDimensions: {},
    feature: null,
    tenant: null,
    user: null,
    endpoint: null,
    environment: null,
    workflow: null,
    tool: null,
    middlewareChain: null,
    providerCallCount: 0,
    retryCount: 0,
    retryOverheadUsd: 0,
    cacheSavingsUsd: 0,
  }
}

function normalizeCostDimensions(value: unknown): CostDimensions {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return {}
  const source = value as Record<string, unknown>
  const out: CostDimensions = {}
  for (const key of COST_DIMENSION_KEYS) {
    const v = source[key]
    if (typeof v === 'string' && v !== '') out[key] = v
  }
  return out
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function round8(value: number): number {
  return Math.round(value * 1e8) / 1e8
}
