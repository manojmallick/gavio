/** auditInterceptor (F-OBS-01) — captures a full record of every call. */

import type { InterceptorContext } from '../../context.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { PromptLineage } from '../../types.js'
import type { Interceptor } from '../base.js'
import { AuditRecord } from './record.js'
import type { AuditSink } from './sink.js'
import { stdoutSink } from './sinks/stdout.js'

const PROMPT_HASH_KEY = 'audit_prompt_hash'
const LINEAGE_KEY = 'audit_lineage'

export const AUDIT_NAME = 'audit'

export interface AuditInterceptorOptions {
  sink?: AuditSink | 'stdout'
  /** F-OBS-02: link each record via previousHash into a tamper-evident chain. */
  hashChain?: boolean
}

/**
 * Build an AuditRecord per request and write it to a sink.
 *
 * Register this as the outermost interceptor so its `after` runs last and sees
 * the final, fully-processed response. It hashes the (already PII-redacted)
 * prompt in `before` and the response in `after` — content is never stored,
 * only digests and metadata.
 */
class AuditInterceptor implements Interceptor {
  readonly name = AUDIT_NAME
  readonly dryRunSafe = true // auditing is observation-only, so it always runs

  private readonly sink: AuditSink
  private readonly hashChain: boolean
  private lastHash = ''

  constructor(options: AuditInterceptorOptions = {}) {
    this.sink = resolveSink(options.sink)
    this.hashChain = options.hashChain ?? false
  }

  async before(request: GavioRequest, ctx: InterceptorContext): Promise<GavioRequest> {
    ctx.state[PROMPT_HASH_KEY] = AuditRecord.hashText(request.promptText())
    if (request.lineage != null) ctx.state[LINEAGE_KEY] = request.lineage
    return request
  }

  async after(
    response: GavioResponse,
    ctx: InterceptorContext,
  ): Promise<GavioResponse> {
    const record = new AuditRecord({
      traceId: response.traceId,
      parentTraceId: ctx.parentTraceId,
      agentId: ctx.agentId,
      sessionId: ctx.sessionId,
      timestampUtc: AuditRecord.nowUtc(),
      provider: response.provider,
      model: response.model,
      modelVersion: response.modelVersion,
      promptHash: (ctx.state[PROMPT_HASH_KEY] as string | undefined) ?? '',
      responseHash: AuditRecord.hashText(response.content),
      tokenUsage: response.usage,
      costUsd: response.costUsd,
      latencyMs: response.latencyMs,
      piiEntityTypes: [...ctx.piiEntityTypes],
      piiEntityCounts: { ...ctx.piiEntityCounts },
      interceptorsFired: [...ctx.interceptorsFired],
      cacheHit: response.cacheHit,
      cacheType: response.cacheType,
      guardrailOutcome: ctx.guardrailOutcome,
      riskScore: ctx.riskScore,
      lineage: (ctx.state[LINEAGE_KEY] as PromptLineage | undefined) ?? null,
    })
    if (this.hashChain) {
      record.previousHash = this.lastHash
      this.lastHash = record.contentHash()
    }
    response.audit = record
    try {
      await this.sink.write(record)
    } catch {
      // Auditing must never break the call.
      // eslint-disable-next-line no-console
      console.error(`[gavio:audit] sink write failed for trace ${record.traceId}`)
    }
    return response
  }
}

function resolveSink(sink: AuditSink | 'stdout' | undefined): AuditSink {
  if (sink === undefined || sink === 'stdout') return stdoutSink()
  return sink
}

/** Factory: build an audit interceptor. */
export function auditInterceptor(options: AuditInterceptorOptions = {}): Interceptor {
  return new AuditInterceptor(options)
}

/** True if an interceptor is the audit interceptor (used by dev-mode auto-wiring). */
export function isAuditInterceptor(i: Interceptor): boolean {
  return i.name === AUDIT_NAME
}
