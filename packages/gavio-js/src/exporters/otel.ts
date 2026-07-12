/** OpenTelemetry-style runtime span export. */

import { appendFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import { createHash } from 'node:crypto'
import type { GavioRuntimeEvent, GavioRuntimeExporter } from './base.js'
import { metadataOnlyEvent } from './base.js'

export interface OtelSpan {
  traceId: string
  spanId: string
  parentSpanId: string | null
  name: string
  kind: 'INTERNAL'
  startTimeUnixNano: number
  endTimeUnixNano: number
  status: { code: 'OK' | 'ERROR' | 'UNSET'; message?: string }
  attributes: Record<string, unknown>
  events: Array<{ name: string; timeUnixNano: number; attributes: Record<string, unknown> }>
}

export interface OtelSpanExporterOptions {
  path?: string
  write?: (span: OtelSpan) => void
  writeLine?: (line: string) => void
  serviceName?: string
  metadataOnly?: boolean
}

export function otelSpanExporter(options: OtelSpanExporterOptions = {}): GavioRuntimeExporter {
  const destinations = [options.path, options.write, options.writeLine].filter(
    (value) => value !== undefined,
  ).length
  if (destinations === 0) throw new Error('otelSpanExporter requires path, write, or writeLine')
  if (destinations > 1) throw new Error('pass only one of path, write, or writeLine')
  if (options.path !== undefined) mkdirSync(dirname(options.path), { recursive: true })
  const mapper = new OtelSpanMapper({ serviceName: options.serviceName ?? 'gavio' })
  const metadataOnly = options.metadataOnly ?? true
  const writeLine =
    options.writeLine ?? ((line: string) => appendFileSync(options.path!, line, 'utf8'))

  return {
    exportEvent(event: GavioRuntimeEvent): void {
      const payload = metadataOnly ? metadataOnlyEvent(event) : structuredClone(event)
      for (const span of mapper.consume(payload)) {
        if (options.write !== undefined) options.write(span)
        else writeLine(JSON.stringify(span) + '\n')
      }
    },
  }
}

export function otelSpansFromEvents(
  events: GavioRuntimeEvent[],
  options: { serviceName?: string; metadataOnly?: boolean } = {},
): OtelSpan[] {
  const mapper = new OtelSpanMapper({ serviceName: options.serviceName ?? 'gavio' })
  const metadataOnly = options.metadataOnly ?? true
  const spans: OtelSpan[] = []
  for (const event of events) {
    const payload = metadataOnly ? metadataOnlyEvent(event) : structuredClone(event)
    spans.push(...mapper.consume(payload))
  }
  return spans
}

class OtelSpanMapper {
  private readonly serviceName: string
  private readonly traces = new Map<string, TraceState>()

  constructor(options: { serviceName: string }) {
    this.serviceName = options.serviceName
  }

  consume(event: GavioRuntimeEvent): OtelSpan[] {
    const traceId = event.traceId
    if (event.type === 'trace.start') {
      this.traces.set(traceId, new TraceState(event, this.serviceName))
      return []
    }
    const state = this.traces.get(traceId)
    if (state === undefined) return []
    if (event.type.endsWith('.start')) {
      state.openSpan(event)
      return []
    }
    if (event.type === 'interceptor.before.end' || event.type === 'interceptor.after.end') {
      return state.closeInterceptor(event)
    }
    if (event.type === 'provider.call.end') return state.closeProvider(event)
    if (event.type === 'trace.error') {
      state.addException(event)
      return []
    }
    if (event.type === 'governance.event') {
      state.addEvent('gavio.governance', event, { ...event.data })
      return []
    }
    if (event.type === 'trace.end') {
      const span = state.closeRoot(event)
      this.traces.delete(traceId)
      return [span]
    }
    return []
  }
}

class TraceState {
  private readonly startEvent: GavioRuntimeEvent
  private readonly serviceName: string
  private readonly originalTraceId: string
  private readonly otelTraceId: string
  private readonly rootSpanId: string
  private readonly rootStartNs: number
  private readonly open = new Map<string, GavioRuntimeEvent[]>()
  private readonly rootEvents: OtelSpan['events'] = []

  constructor(startEvent: GavioRuntimeEvent, serviceName: string) {
    this.startEvent = startEvent
    this.serviceName = serviceName
    this.originalTraceId = startEvent.traceId
    this.otelTraceId = hexId(this.originalTraceId, 32)
    this.rootSpanId = hexId(`${this.originalTraceId}:root`, 16)
    this.rootStartNs = wallTimeNs(asString(startEvent.data['wallTimeUtc']))
  }

  openSpan(event: GavioRuntimeEvent): void {
    const key = openKey(event)
    const list = this.open.get(key) ?? []
    list.push(event)
    this.open.set(key, list)
  }

  closeInterceptor(endEvent: GavioRuntimeEvent): OtelSpan[] {
    const phase = endEvent.type === 'interceptor.before.end' ? 'before' : 'after'
    const name = asString(endEvent.data['name']) ?? 'unknown'
    const startEvent = this.popOpen(`interceptor.${phase}`, name)
    if (startEvent === undefined) return []
    const attributes = this.baseAttributes()
    attributes['gavio.interceptor.name'] = name
    attributes['gavio.interceptor.phase'] = phase
    attributes['gavio.interceptor.mutated'] = Boolean(endEvent.data['mutated'])
    copyIfPresent(attributes, endEvent.data, 'durationUs', 'gavio.duration_us')
    const decision = recordValue(endEvent.data['decision'])
    if (decision !== undefined) {
      Object.assign(attributes, flatten('gavio.decision', decision))
    }
    return [
      this.span({
        name: `gavio.interceptor.${phase} ${name}`,
        logicalKey: `interceptor.${phase}:${name}:${startEvent.seq}`,
        startEvent,
        endEvent,
        attributes,
        error: false,
      }),
    ]
  }

  closeProvider(endEvent: GavioRuntimeEvent): OtelSpan[] {
    const startEvent = this.popOpen('provider.call', endEvent.data['attempt'])
    if (startEvent === undefined) return []
    const model = asString(startEvent.data['model']) ?? asString(this.startEvent.data['model']) ?? 'unknown'
    const provider =
      asString(startEvent.data['provider']) ?? asString(this.startEvent.data['provider']) ?? 'unknown'
    const attributes = this.baseAttributes()
    attributes['gen_ai.system'] = provider
    attributes['gen_ai.request.model'] = model
    copyIfPresent(attributes, endEvent.data, 'modelVersion', 'gen_ai.response.model')
    copyIfPresent(attributes, endEvent.data, 'attempt', 'gavio.retry.attempt')
    copyIfPresent(attributes, endEvent.data, 'costUsd', 'gen_ai.usage.cost')
    copyIfPresent(attributes, endEvent.data, 'durationUs', 'gavio.duration_us')
    copyIfPresent(attributes, endEvent.data, 'errorType', 'error.type')
    const usage = recordValue(endEvent.data['usage'])
    if (usage !== undefined) {
      copyIfPresent(attributes, usage, 'promptTokens', 'gen_ai.usage.input_tokens')
      copyIfPresent(attributes, usage, 'completionTokens', 'gen_ai.usage.output_tokens')
      copyIfPresent(attributes, usage, 'totalTokens', 'gen_ai.usage.total_tokens')
    }
    const status = asString(endEvent.data['status']) ?? 'ok'
    return [
      this.span({
        name: `chat ${model}`,
        logicalKey: `provider:${String(endEvent.data['attempt'] ?? startEvent.seq)}`,
        startEvent,
        endEvent,
        attributes,
        error: status !== 'ok',
        statusMessage: asString(endEvent.data['errorType']),
      }),
    ]
  }

  addException(event: GavioRuntimeEvent): void {
    const attributes: Record<string, unknown> = {
      'exception.type': event.data['errorType'] ?? 'Error',
      'exception.message': event.data['message'] ?? '',
      'gavio.error.origin': event.data['origin'] ?? 'chain',
      'exception.escaped': !Boolean(event.data['handled']),
    }
    copyIfPresent(attributes, event.data, 'interceptorName', 'gavio.interceptor.name')
    this.addEvent('exception', event, attributes)
  }

  addEvent(name: string, event: GavioRuntimeEvent, attributes: Record<string, unknown>): void {
    this.rootEvents.push({
      name,
      timeUnixNano: this.timeNs(event),
      attributes: clean(attributes),
    })
  }

  closeRoot(endEvent: GavioRuntimeEvent): OtelSpan {
    const attributes = this.baseAttributes()
    copyIfPresent(attributes, this.startEvent.data, 'agentId', 'gavio.agent_id')
    copyIfPresent(attributes, this.startEvent.data, 'sessionId', 'session.id')
    copyIfPresent(attributes, this.startEvent.data, 'parentTraceId', 'gavio.parent_trace_id')
    copyIfPresent(attributes, this.startEvent.data, 'provider', 'gen_ai.system')
    copyIfPresent(attributes, this.startEvent.data, 'model', 'gen_ai.request.model')
    copyIfPresent(attributes, endEvent.data, 'latencyMs', 'gavio.latency_ms')
    copyIfPresent(attributes, endEvent.data, 'costUsd', 'gen_ai.usage.cost')
    copyIfPresent(attributes, endEvent.data, 'cacheHit', 'gavio.cache.hit')
    copyIfPresent(attributes, endEvent.data, 'cacheType', 'gavio.cache.type')
    copyIfPresent(attributes, endEvent.data, 'piiEntityTypes', 'gavio.pii.entity_types')
    copyIfPresent(attributes, endEvent.data, 'interceptorsFired', 'gavio.interceptors')
    const dimensions = recordValue(this.startEvent.data['costDimensions'])
    if (dimensions !== undefined) {
      for (const [key, value] of Object.entries(dimensions)) {
        attributes[`gavio.cost.dimension.${key}`] = value
      }
    }
    const status = asString(endEvent.data['status']) ?? 'ok'
    return this.span({
      name: 'gavio.request',
      logicalKey: 'root',
      startEvent: this.startEvent,
      endEvent,
      attributes,
      error: status !== 'ok',
      statusMessage: status,
      parentSpanId: null,
      spanId: this.rootSpanId,
      events: [...this.rootEvents],
    })
  }

  private span(options: {
    name: string
    logicalKey: string
    startEvent: GavioRuntimeEvent
    endEvent: GavioRuntimeEvent
    attributes: Record<string, unknown>
    error: boolean
    statusMessage?: string
    parentSpanId?: string | null
    spanId?: string
    events?: OtelSpan['events']
  }): OtelSpan {
    const status: OtelSpan['status'] = { code: options.error ? 'ERROR' : 'OK' }
    if (options.error && options.statusMessage !== undefined && options.statusMessage !== '') {
      status.message = options.statusMessage
    }
    const parent =
      options.parentSpanId === undefined
        ? this.rootSpanId
        : options.parentSpanId === null
          ? null
          : options.parentSpanId
    return {
      traceId: this.otelTraceId,
      spanId: options.spanId ?? hexId(`${this.originalTraceId}:${options.logicalKey}`, 16),
      parentSpanId: parent,
      name: options.name,
      kind: 'INTERNAL',
      startTimeUnixNano: this.timeNs(options.startEvent),
      endTimeUnixNano: this.timeNs(options.endEvent),
      status,
      attributes: clean(options.attributes),
      events: options.events ?? [],
    }
  }

  private baseAttributes(): Record<string, unknown> {
    return {
      'service.name': this.serviceName,
      'gavio.trace_id': this.originalTraceId,
      'gavio.event.schema_version': this.startEvent.schemaVersion,
    }
  }

  private popOpen(family: string, discriminator: unknown): GavioRuntimeEvent | undefined {
    const exact = `${family}:${String(discriminator)}`
    const exactList = this.open.get(exact)
    if (exactList !== undefined && exactList.length > 0) return exactList.pop()
    for (const [key, list] of this.open) {
      if (key.startsWith(`${family}:`) && list.length > 0) return list.pop()
    }
    return undefined
  }

  private timeNs(event: GavioRuntimeEvent): number {
    return this.rootStartNs + Number(event.tNs ?? 0)
  }
}

function openKey(event: GavioRuntimeEvent): string {
  if (event.type === 'interceptor.before.start') return `interceptor.before:${String(event.data['name'])}`
  if (event.type === 'interceptor.after.start') return `interceptor.after:${String(event.data['name'])}`
  if (event.type === 'provider.call.start') return `provider.call:${String(event.data['attempt'])}`
  return `${event.type}:${event.seq}`
}

function wallTimeNs(value: string | undefined): number {
  if (value === undefined || value === '') return 0
  const ms = Date.parse(value)
  return Number.isFinite(ms) ? ms * 1_000_000 : 0
}

function hexId(seed: string, length: number): string {
  return createHash('sha256').update(seed).digest('hex').slice(0, length)
}

function flatten(prefix: string, value: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(value)) {
    const name = `${prefix}.${key}`
    const nested = recordValue(raw)
    if (nested !== undefined) Object.assign(out, flatten(name, nested))
    else out[name] = raw
  }
  return out
}

function copyIfPresent(
  target: Record<string, unknown>,
  source: Record<string, unknown>,
  sourceKey: string,
  targetKey: string,
): void {
  const value = source[sourceKey]
  if (value !== undefined && value !== null) target[targetKey] = value
}

function recordValue(value: unknown): Record<string, unknown> | undefined {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return undefined
  return value as Record<string, unknown>
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined
}

function clean(value: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(value)) {
    if (raw !== undefined && raw !== null) out[key] = raw
  }
  return out
}
