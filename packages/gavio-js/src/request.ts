/** GavioRequest — the canonical, provider-agnostic request model. */

import { canonicalJson } from './canonical-json.js'
import { newTraceId } from './ids.js'
import { messageText, messageToolCalls } from './tool-calling.js'
import { coerceProvider, PromptLineage } from './types.js'
import type {
  Message,
  PromptLineageInit,
  Provider,
  ToolChoice,
  ToolDefinition,
} from './types.js'

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
  /** Binary image inputs, scanned for PII before a provider call (F-SEC-09). */
  images?: Uint8Array[]
  lineage?: PromptLineage | PromptLineageInit | null
  /** Tools the model may call — `ToolDefinition` objects (F-ADP-03). */
  tools?: ToolDefinition[] | null
  /** `auto`, `none`, `required` or `{ name }`. Ignored when `tools` is empty (F-ADP-03). */
  toolChoice?: ToolChoice | null
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
  images: Uint8Array[]
  lineage: PromptLineage | null
  tools: ToolDefinition[] | null
  toolChoice: ToolChoice | null

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
    this.images = init.images ?? []
    this.lineage = init.lineage != null ? PromptLineage.from(init.lineage) : null
    this.tools = init.tools ?? null
    this.toolChoice = init.toolChoice ?? null
  }

  get temperature(): number {
    const t = this.options['temperature']
    return typeof t === 'number' ? t : 0.7
  }

  get maxTokens(): number {
    const m = this.options['maxTokens'] ?? this.options['max_tokens']
    return typeof m === 'number' ? m : 1024
  }

  /**
   * Concatenate message contents — used for hashing and token estimation.
   *
   * Tool calls an assistant message made are included as `name(canonical-args)` so the
   * hash and the estimate see them; messages without tool calls contribute exactly
   * their content, as before.
   */
  promptText(): string {
    const parts: string[] = []
    for (const message of this.messages) {
      parts.push(messageText(message))
      for (const call of messageToolCalls(message)) {
        parts.push(`${call.name}(${canonicalJson(call.arguments)})`)
      }
    }
    return parts.join('\n')
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
      images: this.images,
      lineage: this.lineage,
      tools: this.tools !== null ? [...this.tools] : null,
      toolChoice:
        typeof this.toolChoice === 'object' && this.toolChoice !== null
          ? { ...this.toolChoice }
          : this.toolChoice,
    })
  }
}
