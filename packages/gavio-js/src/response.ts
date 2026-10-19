/** GavioResponse — the canonical response returned to the caller. */

import { ToolCall, TokenUsage } from './types.js'
import type { CacheType, FinishReason, Message, ToolCallInit } from './types.js'
import type { AuditRecord } from './interceptors/audit/record.js'

export interface GavioResponseInit {
  traceId: string
  content: string
  model: string
  provider: string
  modelVersion?: string
  usage?: TokenUsage
  costUsd?: number
  latencyMs?: number
  cacheHit?: boolean
  cacheType?: CacheType | null
  interceptorsFired?: string[]
  audit?: AuditRecord | null
  metadata?: Record<string, unknown>
  embeddings?: number[][] | null
  /** Tool calls the model requested, in provider order (F-ADP-03). */
  toolCalls?: ReadonlyArray<ToolCall | ToolCallInit>
  finishReason?: FinishReason | null
}

/** Result of a gateway call, enriched by the post-interceptor pipeline. */
export class GavioResponse {
  traceId: string
  content: string
  model: string
  provider: string
  modelVersion: string
  usage: TokenUsage
  costUsd: number
  latencyMs: number
  cacheHit: boolean
  cacheType: CacheType | null
  interceptorsFired: string[]
  audit: AuditRecord | null
  metadata: Record<string, unknown>
  /** Set on embedding calls (F-SEC-10): one vector per input text. */
  embeddings: number[][] | null
  /**
   * Tool calls the model requested, in provider order (F-ADP-03). `content` is empty
   * when the model only called tools.
   */
  toolCalls: ToolCall[]
  finishReason: FinishReason | null

  constructor(init: GavioResponseInit) {
    this.traceId = init.traceId
    this.content = init.content
    this.model = init.model
    this.provider = init.provider
    this.modelVersion = init.modelVersion ?? ''
    this.usage = init.usage ?? new TokenUsage()
    this.costUsd = init.costUsd ?? 0.0
    this.latencyMs = init.latencyMs ?? 0
    this.cacheHit = init.cacheHit ?? false
    this.cacheType = init.cacheType ?? null
    this.interceptorsFired = init.interceptorsFired ?? []
    this.audit = init.audit ?? null
    this.metadata = init.metadata ?? {}
    this.embeddings = init.embeddings ?? null
    this.toolCalls = (init.toolCalls ?? []).map((call) => ToolCall.from(call))
    this.finishReason = init.finishReason ?? null
  }

  /**
   * Return a copy with `changes` applied (used by PII restore, guardrails).
   *
   * Every field is carried over, so a field added to the response later cannot be
   * silently dropped by an interceptor that rewrites only `content`.
   */
  copyWith(changes: Partial<GavioResponseInit> = {}): GavioResponse {
    return new GavioResponse({
      traceId: this.traceId,
      content: this.content,
      model: this.model,
      provider: this.provider,
      modelVersion: this.modelVersion,
      usage: this.usage,
      costUsd: this.costUsd,
      latencyMs: this.latencyMs,
      cacheHit: this.cacheHit,
      cacheType: this.cacheType,
      interceptorsFired: [...this.interceptorsFired],
      audit: this.audit,
      metadata: { ...this.metadata },
      embeddings: this.embeddings,
      toolCalls: [...this.toolCalls],
      finishReason: this.finishReason,
      ...changes,
    })
  }

  /** Return a copy with replaced content (used by PII restore, guardrails). */
  copyWithContent(content: string): GavioResponse {
    return this.copyWith({ content })
  }

  /** The assistant message to append to the conversation before the tool results. */
  toAssistantMessage(): Message {
    const message: Message = { role: 'assistant', content: this.content }
    if (this.toolCalls.length > 0) {
      message['toolCalls'] = this.toolCalls.map((call) => call.toJSON())
    }
    return message
  }
}
