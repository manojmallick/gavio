/**
 * Inspector event constructors — the camelCase wire format of
 * spec/InspectorEvent.schema.json.
 *
 * Content gating is structural, not a filter: the metadata-mode builders
 * (`traceStartData`, `interceptorEndData`, `traceEndData`) have no content
 * parameters at all, so content can never reach a metadata-mode event. The
 * `...WithMessages` / `...WithDiff` / `...WithContent` builders exist only for
 * 'full' and 'redacted' modes and mask secrets before anything is emitted.
 */

import { uuid7 } from '../ids.js'
import { resolveOverlaps } from '../interceptors/pii/guard.js'
import { ScanContext } from '../interceptors/pii/context.js'
import type { PiiMatch } from '../interceptors/pii/match.js'
import { secretScanner } from '../interceptors/pii/scanners/secret.js'
import type { Message } from '../types.js'
import type { InspectorMode } from './config.js'

export type InspectorEventType =
  | 'trace.start'
  | 'interceptor.before.start'
  | 'interceptor.before.end'
  | 'provider.call.start'
  | 'provider.call.end'
  | 'interceptor.after.start'
  | 'interceptor.after.end'
  | 'trace.end'
  | 'trace.error'

/** One span event on the inspector bus. */
export interface InspectorEvent {
  schemaVersion: '1.0'
  eventId: string
  traceId: string
  type: InspectorEventType
  /** Monotonic nanoseconds since trace.start. */
  tNs: number
  /** Per-trace ordering tiebreaker, starts at 0. */
  seq: number
  data: Record<string, unknown>
}

/** Envelope builder — every event flows through here. */
export function makeEvent(
  traceId: string,
  type: InspectorEventType,
  tNs: number,
  seq: number,
  data: Record<string, unknown>,
): InspectorEvent {
  return { schemaVersion: '1.0', eventId: uuid7(), traceId, type, tNs, seq, data }
}

// ── Secret masking ───────────────────────────────────────────────────────────

/**
 * Replace every span matched by the secret scanner (API keys, JWTs, private
 * keys, DB connection strings) with `***`. Applied to all content-bearing
 * strings in full/redacted modes — including full-mode `from` text.
 */
export function maskSecrets(text: string): string {
  const matches = secretScanner().scan(text, new ScanContext()) as PiiMatch[]
  if (matches.length === 0) return text
  let out = text
  for (const m of [...resolveOverlaps(matches)].sort((a, b) => b.start - a.start)) {
    out = out.slice(0, m.start) + '***' + out.slice(m.end)
  }
  return out
}

// ── trace.start ──────────────────────────────────────────────────────────────

export interface TraceStartMeta {
  parentTraceId: string | null
  agentId: string | null
  sessionId: string | null
  provider: string
  model: string
  wallTimeUtc: string
  mode: InspectorMode
}

/** Metadata mode — no message parameter exists on this path. */
export function traceStartData(meta: TraceStartMeta): Record<string, unknown> {
  return { ...meta }
}

/** Full/redacted modes — messages included, secrets masked. */
export function traceStartDataWithMessages(
  meta: TraceStartMeta,
  messages: Message[],
): Record<string, unknown> {
  return {
    ...meta,
    messages: messages.map((m) => ({ role: m.role, content: maskSecrets(m.content ?? '') })),
  }
}

// ── interceptor.*.start / interceptor.*.end ─────────────────────────────────

export function interceptorStartData(name: string): Record<string, unknown> {
  return { name }
}

export interface InterceptorEndMeta {
  name: string
  durationUs: number
  mutated: boolean
  decision?: Record<string, unknown>
}

/**
 * Request/response mutation diff. `from` fields exist only in full mode;
 * redacted mode carries `to` only; metadata mode has no diff at all.
 */
export interface MutationDiff {
  messages?: Array<{ index: number; from?: string; to: string }>
  model?: { from?: string; to: string }
  content?: { from?: string; to: string }
}

/** Metadata mode — no diff parameter exists on this path. */
export function interceptorEndData(meta: InterceptorEndMeta): Record<string, unknown> {
  const data: Record<string, unknown> = {
    name: meta.name,
    durationUs: meta.durationUs,
    mutated: meta.mutated,
  }
  if (meta.decision !== undefined) data['decision'] = meta.decision
  return data
}

/** Full/redacted modes — diff attached when the interceptor mutated anything. */
export function interceptorEndDataWithDiff(
  meta: InterceptorEndMeta,
  diff: MutationDiff | undefined,
): Record<string, unknown> {
  const data = interceptorEndData(meta)
  if (diff !== undefined) data['diff'] = diff
  return data
}

// ── provider.call.* ──────────────────────────────────────────────────────────

export function providerCallStartData(
  provider: string,
  model: string,
  attempt: number,
): Record<string, unknown> {
  return { provider, model, attempt }
}

export interface ProviderCallEndMeta {
  durationUs: number
  status: 'ok' | 'error'
  errorType?: string
  modelVersion?: string
  usage?: { promptTokens: number; completionTokens: number; totalTokens: number }
}

export function providerCallEndData(meta: ProviderCallEndMeta): Record<string, unknown> {
  const data: Record<string, unknown> = { durationUs: meta.durationUs, status: meta.status }
  if (meta.errorType !== undefined) data['errorType'] = meta.errorType
  if (meta.modelVersion !== undefined && meta.modelVersion !== '') {
    data['modelVersion'] = meta.modelVersion
  }
  if (meta.usage !== undefined) data['usage'] = meta.usage
  return data
}

// ── trace.end / trace.error ──────────────────────────────────────────────────

export interface TraceEndMeta {
  status: 'ok' | 'error' | 'blocked'
  latencyMs: number
  costUsd?: number
  cacheHit?: boolean
  cacheType?: string | null
  interceptorsFired: string[]
  piiEntityTypes?: string[]
}

/** Metadata mode (and every error path) — no content parameter exists here. */
export function traceEndData(meta: TraceEndMeta): Record<string, unknown> {
  const data: Record<string, unknown> = {
    status: meta.status,
    latencyMs: meta.latencyMs,
    interceptorsFired: [...meta.interceptorsFired],
  }
  if (meta.costUsd !== undefined) data['costUsd'] = meta.costUsd
  if (meta.cacheHit !== undefined) data['cacheHit'] = meta.cacheHit
  if (meta.cacheType !== undefined) data['cacheType'] = meta.cacheType
  if (meta.piiEntityTypes !== undefined) data['piiEntityTypes'] = [...meta.piiEntityTypes]
  return data
}

/** Full/redacted modes — final response content included, secrets masked. */
export function traceEndDataWithContent(
  meta: TraceEndMeta,
  content: string,
): Record<string, unknown> {
  const data = traceEndData(meta)
  data['content'] = maskSecrets(content)
  return data
}

export interface TraceErrorMeta {
  origin: 'interceptor' | 'provider' | 'chain'
  interceptorName?: string
  errorType: string
  message: string
  handled: boolean
}

export function traceErrorData(meta: TraceErrorMeta): Record<string, unknown> {
  const data: Record<string, unknown> = {
    origin: meta.origin,
    errorType: meta.errorType,
    message: meta.message,
    handled: meta.handled,
  }
  if (meta.interceptorName !== undefined) data['interceptorName'] = meta.interceptorName
  return data
}
