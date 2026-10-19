/** Shared enums (as string unions / const objects) and utility types. */

import { createHash } from 'node:crypto'

import { canonicalJson } from './canonical-json.js'

/**
 * A provider-agnostic chat message.
 *
 * Tool calling (F-ADP-03) adds three optional keys, spelled as in the spec: an
 * assistant message may carry `toolCalls` (a list of {@link ToolCall}-shaped
 * objects) and a `role: "tool"` message `toolCallId`, `name` and `isError`.
 * The snake_case spellings Python uses (`tool_calls`, `tool_call_id`,
 * `is_error`) are accepted on read.
 */
export interface Message {
  role: string
  content: string
  [key: string]: unknown
}

/** Supported LLM providers. String-valued for easy config + logging. */
export const Provider = {
  OPENAI: 'openai',
  ANTHROPIC: 'anthropic',
  GEMINI: 'gemini',
  AZURE_OPENAI: 'azure_openai',
  OPENROUTER: 'openrouter',
  OLLAMA: 'ollama',
  BEDROCK: 'bedrock',
  COHERE: 'cohere',
  MOCK: 'mock',
} as const

export type Provider = (typeof Provider)[keyof typeof Provider]

/** Accept either a known provider value or any string and normalise to lowercase. */
export function coerceProvider(value: Provider | string): Provider {
  return value.toLowerCase() as Provider
}

export const CacheType = {
  EXACT: 'exact',
  SEMANTIC: 'semantic',
} as const

export type CacheType = (typeof CacheType)[keyof typeof CacheType]

/** What PiiGuard does with a detected entity. */
export const PiiMode = {
  REDACT: 'redact', // replace with a typed placeholder token
  MASK: 'mask', // replace characters with asterisks
  TAG: 'tag', // annotate inline but keep the value
  BLOCK: 'block', // raise and refuse the request
} as const

export type PiiMode = (typeof PiiMode)[keyof typeof PiiMode]

export const Sensitivity = {
  STRICT: 'strict',
  BALANCED: 'balanced',
  PERMISSIVE: 'permissive',
} as const

export type Sensitivity = (typeof Sensitivity)[keyof typeof Sensitivity]

export const GuardrailOutcome = {
  PASS: 'PASS',
  FAIL: 'FAIL',
  HITL: 'HITL',
} as const

export type GuardrailOutcome = (typeof GuardrailOutcome)[keyof typeof GuardrailOutcome]

/** Provider-agnostic stop reason (F-ADP-03). Mapped from each provider's native value. */
export const FinishReason = {
  STOP: 'stop',
  LENGTH: 'length',
  TOOL_CALLS: 'tool_calls',
  CONTENT_FILTER: 'content_filter',
  OTHER: 'other',
} as const

export type FinishReason = (typeof FinishReason)[keyof typeof FinishReason]

/**
 * How the model may use the declared tools (F-ADP-03): `auto`, `none`,
 * `required`, or `{ name }` to force one specific tool.
 */
export type ToolChoice = 'auto' | 'none' | 'required' | { name: string }

/**
 * A tool the model may call — the `ToolDefinition` spec shape. Only `name`,
 * `description` and `inputSchema` (or `input_schema`) reach the provider; the
 * governance fields (permissions, risk, ...) stay local for the Tool Runtime.
 */
export interface ToolDefinition {
  name: string
  description?: string
  inputSchema?: Record<string, unknown>
  input_schema?: Record<string, unknown>
  [key: string]: unknown
}

export interface ToolCallInit {
  id: string
  name: string
  arguments?: Record<string, unknown> | null
}

/**
 * A model-requested tool invocation, normalised across providers (F-ADP-03).
 *
 * `arguments` is always a parsed JSON object. `id` is the provider's call id, or
 * a deterministic synthetic id (`call_<index>_<name>`) for providers that send
 * none (Gemini, Ollama).
 */
export class ToolCall {
  readonly id: string
  readonly name: string
  readonly arguments: Record<string, unknown>

  constructor(init: ToolCallInit) {
    this.id = init.id
    this.name = init.name
    this.arguments = init.arguments ?? {}
  }

  /**
   * Coerce a spec-shaped object (or a ToolCall) into a ToolCall, validating it.
   * Throws a `TypeError` when the value is not an object or `arguments` is not
   * a JSON object.
   */
  static from(data: unknown): ToolCall {
    if (data instanceof ToolCall) return data
    if (!isPlainObject(data)) {
      throw new TypeError(`tool call must be an object, got ${describe(data)}`)
    }
    const args = data['arguments']
    if (args !== undefined && args !== null && !isPlainObject(args)) {
      throw new TypeError('tool call arguments must be a JSON object')
    }
    if (data['id'] == null || data['name'] == null) {
      throw new TypeError('tool call needs an id and a name')
    }
    return new ToolCall({
      id: String(data['id']),
      name: String(data['name']),
      arguments: (args as Record<string, unknown> | undefined | null) ?? {},
    })
  }

  /** SHA-256 hex of the canonical JSON of `arguments` — all an audit record keeps of them. */
  argumentsHash(): string {
    return createHash('sha256').update(canonicalJson(this.arguments), 'utf8').digest('hex')
  }

  /** Build the {@link ToolResult} answering this call. */
  result(content: string, options: { isError?: boolean } = {}): ToolResult {
    return new ToolResult({
      toolCallId: this.id,
      content,
      name: this.name,
      isError: options.isError ?? false,
    })
  }

  toJSON(): { id: string; name: string; arguments: Record<string, unknown> } {
    return { id: this.id, name: this.name, arguments: structuredClone(this.arguments) }
  }
}

export interface ToolResultInit {
  toolCallId: string
  content: string
  name?: string | null
  isError?: boolean
}

/** The outcome of executing a {@link ToolCall} (F-ADP-03). */
export class ToolResult {
  readonly toolCallId: string
  readonly content: string
  readonly name: string | null
  readonly isError: boolean

  constructor(init: ToolResultInit) {
    this.toolCallId = init.toolCallId
    this.content = init.content
    this.name = init.name ?? null
    this.isError = init.isError ?? false
  }

  /** The `role: "tool"` message to append to the conversation. */
  toMessage(): Message {
    const message: Message = { role: 'tool', toolCallId: this.toolCallId, content: this.content }
    if (this.name) message['name'] = this.name
    if (this.isError) message['isError'] = true
    return message
  }

  toJSON(): { toolCallId: string; content: string; name?: string; isError: boolean } {
    const json: { toolCallId: string; content: string; name?: string; isError: boolean } = {
      toolCallId: this.toolCallId,
      content: this.content,
      isError: this.isError,
    }
    if (this.name) json.name = this.name
    return json
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function describe(value: unknown): string {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  return typeof value
}

/** Token accounting for a single completion. */
export class TokenUsage {
  readonly promptTokens: number
  readonly completionTokens: number

  constructor(promptTokens = 0, completionTokens = 0) {
    this.promptTokens = promptTokens
    this.completionTokens = completionTokens
  }

  get totalTokens(): number {
    return this.promptTokens + this.completionTokens
  }

  toJSON(): { promptTokens: number; completionTokens: number; totalTokens: number } {
    return {
      promptTokens: this.promptTokens,
      completionTokens: this.completionTokens,
      totalTokens: this.totalTokens,
    }
  }
}

export interface RagChunkInit {
  source: string
  chunkId?: string | null
  score?: number | null
}

/**
 * A single retrieved source that contributed to a prompt. Carries a *reference*
 * to the source — never the retrieved text — so prompt lineage stays within the
 * audit record's metadata-only contract.
 */
export class RagChunk {
  readonly source: string
  readonly chunkId: string | null
  readonly score: number | null

  constructor(init: RagChunkInit) {
    this.source = init.source
    this.chunkId = init.chunkId ?? null
    this.score = init.score ?? null
  }

  toJSON(): { source: string; chunkId: string | null; score: number | null } {
    return { source: this.source, chunkId: this.chunkId, score: this.score }
  }
}

export interface PromptLineageInit {
  templateId?: string | null
  templateVersion?: string | null
  variables?: Record<string, unknown>
  ragChunks?: Array<RagChunk | RagChunkInit>
}

/**
 * Provenance for a rendered prompt (F-OBS-04): the template, the variable
 * bindings interpolated into it, and the RAG chunk sources retrieved for it.
 *
 * Attached to a GavioRequest by the caller and copied into the AuditRecord so
 * any prompt can be reconstructed and debugged. RAG chunk text is never stored
 * — only source references (see {@link RagChunk}).
 */
export class PromptLineage {
  readonly templateId: string | null
  readonly templateVersion: string | null
  readonly variables: Record<string, unknown>
  readonly ragChunks: RagChunk[]

  constructor(init: PromptLineageInit = {}) {
    this.templateId = init.templateId ?? null
    this.templateVersion = init.templateVersion ?? null
    this.variables = init.variables ?? {}
    this.ragChunks = (init.ragChunks ?? []).map((c) =>
      c instanceof RagChunk ? c : new RagChunk(c),
    )
  }

  /** Coerce a PromptLineage instance or plain init object into a PromptLineage. */
  static from(value: PromptLineage | PromptLineageInit): PromptLineage {
    return value instanceof PromptLineage ? value : new PromptLineage(value)
  }

  toJSON(): {
    templateId: string | null
    templateVersion: string | null
    variables: Record<string, unknown>
    ragChunks: Array<{ source: string; chunkId: string | null; score: number | null }>
  } {
    return {
      templateId: this.templateId,
      templateVersion: this.templateVersion,
      variables: this.variables,
      ragChunks: this.ragChunks.map((c) => c.toJSON()),
    }
  }
}
