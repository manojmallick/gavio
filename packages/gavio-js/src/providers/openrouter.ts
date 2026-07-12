/** openrouterAdapter — OpenAI-compatible Chat Completions API. */

import { ConfigurationError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_BASE_URL = 'https://openrouter.ai/api/v1'

export interface OpenRouterAdapterOptions {
  apiKey?: string
  baseUrl?: string
  timeoutMs?: number
  httpReferer?: string
  appTitle?: string
  pricing?: PricingProvider
}

export class OpenRouterAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  private readonly baseUrl: string
  private readonly timeoutSeconds: number
  private readonly httpReferer: string | undefined
  private readonly appTitle: string | undefined

  constructor(options: OpenRouterAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey = options.apiKey ?? process.env['OPENROUTER_API_KEY']
    this.baseUrl = (
      options.baseUrl ??
      process.env['OPENROUTER_BASE_URL'] ??
      DEFAULT_BASE_URL
    ).replace(/\/+$/, '')
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
    this.httpReferer =
      options.httpReferer ??
      process.env['OPENROUTER_HTTP_REFERER'] ??
      process.env['OPENROUTER_REFERER']
    this.appTitle =
      options.appTitle ??
      process.env['OPENROUTER_APP_TITLE'] ??
      process.env['OPENROUTER_TITLE']
  }

  get providerName(): string {
    return 'openrouter'
  }

  url(): string {
    return `${this.baseUrl}/chat/completions`
  }

  headers(): Record<string, string> {
    if (!this.apiKey) {
      throw new ConfigurationError(
        'OPENROUTER_API_KEY not set (pass apiKey or set the env var)',
      )
    }
    const headers: Record<string, string> = { Authorization: `Bearer ${this.apiKey}` }
    if (this.httpReferer) headers['HTTP-Referer'] = this.httpReferer
    if (this.appTitle) headers['X-OpenRouter-Title'] = this.appTitle
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
      this.url(),
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

/** Factory: build an OpenRouter provider adapter. */
export function openrouterAdapter(
  options: OpenRouterAdapterOptions = {},
): OpenRouterAdapter {
  return new OpenRouterAdapter(options)
}
