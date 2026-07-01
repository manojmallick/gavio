/** openaiAdapter — Chat Completions API (GPT-4o, o1, ...). */

import { ConfigurationError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'https://api.openai.com/v1'

export interface OpenAIAdapterOptions {
  apiKey?: string
  baseUrl?: string
  timeoutMs?: number
  organization?: string
  pricing?: PricingProvider
}

class OpenAIAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  private readonly baseUrl: string
  private readonly timeoutSeconds: number
  private readonly organization: string | undefined

  constructor(options: OpenAIAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey = options.apiKey ?? process.env['OPENAI_API_KEY']
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
    this.organization = options.organization
  }

  get providerName(): string {
    return 'openai'
  }

  private headers(): Record<string, string> {
    if (!this.apiKey) {
      throw new ConfigurationError(
        'OPENAI_API_KEY not set (pass apiKey or set the env var)',
      )
    }
    const headers: Record<string, string> = { Authorization: `Bearer ${this.apiKey}` }
    if (this.organization) headers['OpenAI-Organization'] = this.organization
    return headers
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const payload = {
      model: request.model,
      messages: request.messages,
      temperature: request.temperature,
      max_tokens: request.maxTokens,
    }
    const data = await postJson(
      `${this.baseUrl}/chat/completions`,
      payload,
      this.headers(),
      this.timeoutSeconds,
    )
    const choices = (data['choices'] as Array<Record<string, unknown>>) ?? []
    const message = (choices[0]?.['message'] as Record<string, unknown>) ?? {}
    const content = (message['content'] as string) ?? ''
    const usageData = (data['usage'] as Record<string, number>) ?? {}
    const usage = new TokenUsage(
      usageData['prompt_tokens'] ?? 0,
      usageData['completion_tokens'] ?? 0,
    )
    return this.buildResponse(
      request,
      content,
      usage,
      (data['model'] as string) ?? request.model,
      started,
    )
  }

  async healthCheck(): Promise<boolean> {
    try {
      this.headers()
      return true
    } catch {
      return false
    }
  }
}

/** Factory: build an OpenAI provider adapter. */
export function openaiAdapter(options: OpenAIAdapterOptions = {}): OpenAIAdapter {
  return new OpenAIAdapter(options)
}
