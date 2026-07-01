/** geminiAdapter — Google Generative Language API (generateContent). */

import { ConfigurationError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { TokenUsage, type Message } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta'

export interface GeminiAdapterOptions {
  apiKey?: string
  baseUrl?: string
  timeoutMs?: number
  pricing?: PricingProvider
}

interface GeminiContent {
  role: string
  parts: { text: string }[]
}

/** Map Gavio messages to Gemini contents + a system instruction. */
export function geminiToContents(messages: Message[]): {
  system: string | null
  contents: GeminiContent[]
} {
  let system: string | null = null
  const contents: GeminiContent[] = []
  for (const m of messages) {
    const text = m.content
    if (m.role === 'system') {
      system = system ? `${system}\n${text}` : text
      continue
    }
    contents.push({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text }] })
  }
  return { system, contents }
}

class GeminiAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  private readonly baseUrl: string
  private readonly timeoutSeconds: number

  constructor(options: GeminiAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey =
      options.apiKey ?? process.env['GEMINI_API_KEY'] ?? process.env['GOOGLE_API_KEY']
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
  }

  get providerName(): string {
    return 'gemini'
  }

  private payload(request: GavioRequest): Record<string, unknown> {
    const { system, contents } = geminiToContents(request.messages)
    const payload: Record<string, unknown> = {
      contents,
      generationConfig: {
        temperature: request.temperature,
        maxOutputTokens: request.maxTokens,
      },
    }
    if (system) payload['systemInstruction'] = { parts: [{ text: system }] }
    return payload
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    if (!this.apiKey) throw new ConfigurationError('GEMINI_API_KEY not set')
    const started = performance.now()
    const url = `${this.baseUrl}/models/${request.model}:generateContent?key=${this.apiKey}`
    const data = await postJson(url, this.payload(request), {}, this.timeoutSeconds)
    const candidates = (data['candidates'] as Array<Record<string, unknown>>) ?? [{}]
    const contentObj = (candidates[0]?.['content'] as Record<string, unknown>) ?? {}
    const parts = (contentObj['parts'] as Array<{ text?: string }>) ?? []
    const content = parts.map((p) => p.text ?? '').join('')
    const um = (data['usageMetadata'] as Record<string, number>) ?? {}
    const usage = new TokenUsage(um['promptTokenCount'] ?? 0, um['candidatesTokenCount'] ?? 0)
    return this.buildResponse(request, content, usage, request.model, started)
  }

  async healthCheck(): Promise<boolean> {
    return !!this.apiKey
  }
}

/** Factory: build a Gemini provider adapter. */
export function geminiAdapter(options: GeminiAdapterOptions = {}): GeminiAdapter {
  return new GeminiAdapter(options)
}
