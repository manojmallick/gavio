/** ollamaAdapter — local models via the Ollama chat API. */

import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'http://localhost:11434'

export interface OllamaAdapterOptions {
  baseUrl?: string
  timeoutMs?: number
  pricing?: PricingProvider
}

class OllamaAdapter extends BaseProviderAdapter {
  private readonly baseUrl: string
  private readonly timeoutSeconds: number

  constructor(options: OllamaAdapterOptions = {}) {
    super(options.pricing)
    this.baseUrl = (options.baseUrl ?? process.env['OLLAMA_HOST'] ?? DEFAULT_BASE_URL).replace(
      /\/+$/,
      '',
    )
    this.timeoutSeconds = (options.timeoutMs ?? 60_000) / 1000
  }

  get providerName(): string {
    return 'ollama'
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const payload = {
      model: request.model,
      messages: request.messages,
      stream: false,
      options: { temperature: request.temperature },
    }
    const data = await postJson(`${this.baseUrl}/api/chat`, payload, {}, this.timeoutSeconds)
    const message = (data['message'] as Record<string, unknown>) ?? {}
    const content = (message['content'] as string) ?? ''
    const usage = new TokenUsage(
      (data['prompt_eval_count'] as number) ?? 0,
      (data['eval_count'] as number) ?? 0,
    )
    return this.buildResponse(request, content, usage, (data['model'] as string) ?? request.model, started)
  }

  async healthCheck(): Promise<boolean> {
    return true
  }
}

/** Factory: build an Ollama provider adapter. */
export function ollamaAdapter(options: OllamaAdapterOptions = {}): OllamaAdapter {
  return new OllamaAdapter(options)
}
