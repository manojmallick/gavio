/**
 * TraceEmitter — per-request event emission for the inspector.
 *
 * One instance per trace: it owns the trace clock (process.hrtime origin),
 * the per-trace `seq` counter, and the capture mode. Mode branching happens
 * here, dispatching to the structurally separate constructors in events.js —
 * the metadata paths never receive content.
 */

import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import type { InterceptorContext } from '../context.js'
import type { InspectorBus } from './bus.js'
import type { InspectorMode } from './config.js'
import {
  interceptorEndData,
  interceptorEndDataWithDiff,
  interceptorStartData,
  makeEvent,
  maskSecrets,
  providerCallEndData,
  providerCallStartData,
  traceEndData,
  traceEndDataWithContent,
  traceErrorData,
  traceStartData,
  traceStartDataWithMessages,
} from './events.js'
import type { InspectorEventType, MutationDiff, TraceEndMeta } from './events.js'

export type ErrorOrigin = 'interceptor' | 'provider' | 'chain'

export class TraceEmitter {
  private readonly bus: InspectorBus
  private readonly mode: InspectorMode
  readonly traceId: string
  private readonly t0 = process.hrtime.bigint()
  private seq = 0

  constructor(bus: InspectorBus, mode: InspectorMode, traceId: string) {
    this.bus = bus
    this.mode = mode
    this.traceId = traceId
  }

  /** Monotonic clock for durationUs measurements. */
  now(): bigint {
    return process.hrtime.bigint()
  }

  traceStart(request: GavioRequest): void {
    const meta = {
      parentTraceId: request.parentTraceId,
      agentId: request.agentId,
      sessionId: request.sessionId,
      provider: request.provider as string,
      model: request.model,
      wallTimeUtc: new Date().toISOString(),
      mode: this.mode,
    }
    if (this.mode === 'metadata') {
      this.emit('trace.start', traceStartData(meta))
      return
    }
    this.emit('trace.start', traceStartDataWithMessages(meta, request.messages))
  }

  interceptorStart(phase: 'before' | 'after', name: string): void {
    this.emit(`interceptor.${phase}.start`, interceptorStartData(name))
  }

  interceptorBeforeEnd(
    name: string,
    startedAt: bigint,
    before: GavioRequest,
    after: GavioRequest,
    decision: Record<string, unknown> | undefined,
  ): void {
    const mutated =
      before.model !== after.model ||
      JSON.stringify(before.messages) !== JSON.stringify(after.messages)
    const meta = { name, durationUs: this.usSince(startedAt), mutated, decision }
    if (this.mode === 'metadata') {
      this.emit('interceptor.before.end', interceptorEndData(meta))
      return
    }
    const diff = mutated ? requestDiff(before, after, this.mode === 'full') : undefined
    this.emit('interceptor.before.end', interceptorEndDataWithDiff(meta, diff))
  }

  interceptorAfterEnd(
    name: string,
    startedAt: bigint,
    before: GavioResponse,
    after: GavioResponse,
    decision: Record<string, unknown> | undefined,
  ): void {
    const mutated = before.content !== after.content
    const meta = { name, durationUs: this.usSince(startedAt), mutated, decision }
    if (this.mode === 'metadata') {
      this.emit('interceptor.after.end', interceptorEndData(meta))
      return
    }
    const diff = mutated ? responseDiff(before, after, this.mode === 'full') : undefined
    this.emit('interceptor.after.end', interceptorEndDataWithDiff(meta, diff))
  }

  providerCallStart(provider: string, model: string): void {
    this.emit('provider.call.start', providerCallStartData(provider, model, 1))
  }

  providerCallEndOk(startedAt: bigint, response: GavioResponse): void {
    this.emit(
      'provider.call.end',
      providerCallEndData({
        durationUs: this.usSince(startedAt),
        status: 'ok',
        modelVersion: response.modelVersion,
        usage: response.usage.toJSON(),
      }),
    )
  }

  providerCallEndError(startedAt: bigint, error: unknown): void {
    this.emit(
      'provider.call.end',
      providerCallEndData({
        durationUs: this.usSince(startedAt),
        status: 'error',
        errorType: errorTypeName(error),
      }),
    )
  }

  /** Standalone governance signal (drift detection, F-GOV-07). Metadata only. */
  governanceEvent(data: Record<string, unknown>): void {
    this.emit('governance.event', data)
  }

  traceError(origin: ErrorOrigin, error: unknown, interceptorName?: string): void {
    const meta = {
      origin,
      errorType: errorTypeName(error),
      message: error instanceof Error ? error.message : String(error),
      handled: false,
      ...(interceptorName !== undefined ? { interceptorName } : {}),
    }
    this.emit('trace.error', traceErrorData(meta))
  }

  traceEndOk(response: GavioResponse, ctx: InterceptorContext, latencyMs: number): void {
    const meta: TraceEndMeta = {
      status: 'ok',
      latencyMs,
      costUsd: response.costUsd,
      cacheHit: response.cacheHit || ctx.cacheHit,
      cacheType: response.cacheType ?? ctx.cacheType,
      interceptorsFired: ctx.interceptorsFired,
    }
    if (ctx.piiEntityTypes.length > 0) meta.piiEntityTypes = ctx.piiEntityTypes
    if (this.mode === 'metadata') {
      this.emit('trace.end', traceEndData(meta))
      return
    }
    this.emit('trace.end', traceEndDataWithContent(meta, response.content))
  }

  /** Error path carries no content in any mode — there is no response. */
  traceEndError(ctx: InterceptorContext, latencyMs: number): void {
    const meta: TraceEndMeta = {
      status: 'error',
      latencyMs,
      interceptorsFired: ctx.interceptorsFired,
    }
    if (ctx.piiEntityTypes.length > 0) meta.piiEntityTypes = ctx.piiEntityTypes
    this.emit('trace.end', traceEndData(meta))
  }

  private emit(type: InspectorEventType, data: Record<string, unknown>): void {
    this.bus.emit(
      makeEvent(this.traceId, type, Number(process.hrtime.bigint() - this.t0), this.seq, data),
    )
    this.seq += 1
  }

  private usSince(startedAt: bigint): number {
    return Math.max(0, Number((process.hrtime.bigint() - startedAt) / 1000n))
  }
}

function errorTypeName(error: unknown): string {
  if (error instanceof Error) return error.constructor.name
  return typeof error
}

/** Changed messages/model between the request entering and leaving a `before` hook. */
function requestDiff(
  before: GavioRequest,
  after: GavioRequest,
  includeFrom: boolean,
): MutationDiff | undefined {
  const diff: MutationDiff = {}
  const messages: Array<{ index: number; from?: string; to: string }> = []
  const count = Math.max(before.messages.length, after.messages.length)
  for (let i = 0; i < count; i++) {
    const fromContent = before.messages[i]?.content ?? ''
    const toContent = after.messages[i]?.content ?? ''
    if (fromContent === toContent) continue
    const entry: { index: number; from?: string; to: string } = {
      index: i,
      to: maskSecrets(toContent),
    }
    if (includeFrom) entry.from = maskSecrets(fromContent)
    messages.push(entry)
  }
  if (messages.length > 0) diff.messages = messages
  if (before.model !== after.model) {
    diff.model = includeFrom ? { from: before.model, to: after.model } : { to: after.model }
  }
  return Object.keys(diff).length > 0 ? diff : undefined
}

/** Response-content change made by an `after` hook. */
function responseDiff(
  before: GavioResponse,
  after: GavioResponse,
  includeFrom: boolean,
): MutationDiff | undefined {
  if (before.content === after.content) return undefined
  const content: { from?: string; to: string } = { to: maskSecrets(after.content) }
  if (includeFrom) content.from = maskSecrets(before.content)
  return { content }
}
