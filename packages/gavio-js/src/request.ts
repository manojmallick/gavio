/** GavioRequest — the canonical, provider-agnostic request model. */

import { newTraceId } from './ids.js'
import { coerceProvider } from './types.js'
import type { Message, Provider } from './types.js'

export interface GavioRequestInit {
  messages: Message[]
  model: string
  provider: Provider | string
  traceId?: string
  agentId?: string | null
  parentTraceId?: string | null
  sessionId?: string | null
  options?: Record<string, unknown>
  metadata?: Record<string, unknown>
}

/**
 * A single gateway call. A `traceId` (UUID v7, time-sortable) is assigned
 * automatically if not supplied. `parentTraceId` links calls into a
 * multi-agent DAG.
 */
export class GavioRequest {
  messages: Message[]
  model: string
  provider: Provider
  traceId: string
  agentId: string | null
  parentTraceId: string | null
  sessionId: string | null
  options: Record<string, unknown>
  metadata: Record<string, unknown>

  constructor(init: GavioRequestInit) {
    this.messages = init.messages
    this.model = init.model
    this.provider = coerceProvider(init.provider)
    this.traceId = init.traceId ?? newTraceId()
    this.agentId = init.agentId ?? null
    this.parentTraceId = init.parentTraceId ?? null
    this.sessionId = init.sessionId ?? null
    this.options = init.options ?? {}
    this.metadata = init.metadata ?? {}
  }

  get temperature(): number {
    const t = this.options['temperature']
    return typeof t === 'number' ? t : 0.7
  }

  get maxTokens(): number {
    const m = this.options['maxTokens'] ?? this.options['max_tokens']
    return typeof m === 'number' ? m : 1024
  }

  /** Concatenate message contents — used for hashing and token estimation. */
  promptText(): string {
    return this.messages.map((m) => m.content ?? '').join('\n')
  }

  /** Return a shallow copy with replaced messages (interceptors mutate via this). */
  copyWithMessages(messages: Message[]): GavioRequest {
    return new GavioRequest({
      messages,
      model: this.model,
      provider: this.provider,
      traceId: this.traceId,
      agentId: this.agentId,
      parentTraceId: this.parentTraceId,
      sessionId: this.sessionId,
      options: { ...this.options },
      metadata: { ...this.metadata },
    })
  }
}
