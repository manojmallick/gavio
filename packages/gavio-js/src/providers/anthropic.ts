/** anthropicAdapter — Messages API (Claude Sonnet, Haiku, Opus). */

import { ConfigurationError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'
import type { Message } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'https://api.anthropic.com/v1'
const API_VERSION = '2023-06-01'

export interface AnthropicAdapterOptions {
  apiKey?: string
  baseUrl?: string
  timeoutMs?: number
  pricing?: PricingProvider
}

/**
 * Talks to the Anthropic Messages endpoint. Anthropic splits the system prompt
 * from the message list, so any `role === "system"` messages are extracted into
 * the `system` field.
 */
class AnthropicAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  private readonly baseUrl: string
  private readonly timeoutSeconds: number

  constructor(options: AnthropicAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey = options.apiKey ?? process.env['ANTHROPIC_API_KEY']
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, '')
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
  }

  get providerName(): string {
    return 'anthropic'
  }

  private headers(): Record<string, string> {
    if (!this.apiKey) {
      throw new ConfigurationError(
        'ANTHROPIC_API_KEY not set (pass apiKey or set the env var)',
      )
    }
    return {
      'x-api-key': this.apiKey,
      'anthropic-version': API_VERSION,
    }
  }

  private static splitSystem(messages: Message[]): [string | null, Message[]] {
    const systemParts = messages
      .filter((m) => m.role === 'system')
      .map((m) => m.content)
    const chat = messages.filter((m) => m.role !== 'system')
    const system = systemParts.length > 0 ? systemParts.join('\n') : null
    return [system, chat]
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const [system, chat] = AnthropicAdapter.splitSystem(request.messages)
    const payload: Record<string, unknown> = {
      model: request.model,
      messages: chat,
      max_tokens: request.maxTokens,
      temperature: request.temperature,
    }
    if (system) payload['system'] = system

    const data = await postJson(
      `${this.baseUrl}/messages`,
      payload,
      this.headers(),
      this.timeoutSeconds,
    )
    const blocks = (data['content'] as Array<Record<string, unknown>>) ?? []
    const content = blocks
      .filter((b) => b['type'] === 'text')
      .map((b) => (b['text'] as string) ?? '')
      .join('')
    const usageData = (data['usage'] as Record<string, number>) ?? {}
    const usage = new TokenUsage(
      usageData['input_tokens'] ?? 0,
      usageData['output_tokens'] ?? 0,
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

/** Factory: build an Anthropic provider adapter. */
export function anthropicAdapter(options: AnthropicAdapterOptions = {}): AnthropicAdapter {
  return new AnthropicAdapter(options)
}
