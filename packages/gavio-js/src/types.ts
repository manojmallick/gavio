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
