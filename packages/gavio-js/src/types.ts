/** Shared enums (as string unions / const objects) and utility types. */

/** A provider-agnostic chat message. */
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
