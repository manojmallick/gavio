/**
 * TraceBuffer — a bounded ring buffer that subscribes to the InspectorBus and
 * assembles events into per-trace records: { summary, events[] }.
 *
 * Bounded on both axes: at most `maxTraces` traces (oldest evicted first) and
 * at most `maxEventsPerTrace` events kept per trace.
 */

import type { InspectorEvent } from './events.js'

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
  /** Token usage from provider.call.end — feeds /api/stats and /api/simulate-cost. */
  usage?: { promptTokens: number; completionTokens: number; totalTokens: number }
  /** Content hashes (audit-store summaries) — searchable via /api/traces?q=. */
  promptHash?: string
  responseHash?: string
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
    if (event.type === 'provider.call.end' && event.data['usage'] !== undefined) {
      record.summary.usage = event.data['usage'] as TraceSummary['usage']
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
  }

  private applyEnd(summary: TraceSummary, data: Record<string, unknown>): void {
    summary.status = (data['status'] as string | undefined) ?? summary.status
    summary.latencyMs = (data['latencyMs'] as number | undefined) ?? null
    summary.costUsd = (data['costUsd'] as number | undefined) ?? null
    summary.cacheHit = (data['cacheHit'] as boolean | undefined) ?? null
    summary.cacheType = (data['cacheType'] as string | null | undefined) ?? null
    summary.piiEntityTypes = (data['piiEntityTypes'] as string[] | undefined) ?? []
    summary.interceptorsFired = (data['interceptorsFired'] as string[] | undefined) ?? []
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
  }
}
