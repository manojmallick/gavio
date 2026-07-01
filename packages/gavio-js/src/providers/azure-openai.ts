/** azureOpenaiAdapter — Azure OpenAI deployment-based chat completions. */

import { ConfigurationError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'
import { BaseProviderAdapter } from './base.js'
import { postJson } from './http.js'

const DEFAULT_API_VERSION = '2024-06-01'

export interface AzureOpenAIAdapterOptions {
  apiKey?: string
  endpoint?: string
  deployment?: string
  apiVersion?: string
  timeoutMs?: number
  pricing?: PricingProvider
}

class AzureOpenAIAdapter extends BaseProviderAdapter {
  private readonly apiKey: string | undefined
  readonly endpoint: string
  private readonly deployment: string | undefined
  private readonly apiVersion: string
  private readonly timeoutSeconds: number

  constructor(options: AzureOpenAIAdapterOptions = {}) {
    super(options.pricing)
    this.apiKey = options.apiKey ?? process.env['AZURE_OPENAI_API_KEY']
    this.endpoint = (options.endpoint ?? process.env['AZURE_OPENAI_ENDPOINT'] ?? '').replace(
      /\/+$/,
      '',
    )
    this.deployment = options.deployment ?? process.env['AZURE_OPENAI_DEPLOYMENT']
    this.apiVersion = options.apiVersion ?? DEFAULT_API_VERSION
    this.timeoutSeconds = (options.timeoutMs ?? 30_000) / 1000
  }

  get providerName(): string {
    return 'azure_openai'
  }

  url(request: GavioRequest): string {
    const deployment = this.deployment ?? request.model
    return `${this.endpoint}/openai/deployments/${deployment}/chat/completions?api-version=${this.apiVersion}`
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    if (!this.apiKey || !this.endpoint) {
      throw new ConfigurationError('AZURE_OPENAI_API_KEY and AZURE_OPENAI_ENDPOINT must be set')
    }
    const started = performance.now()
    const payload = {
      messages: request.messages,
      temperature: request.temperature,
      max_tokens: request.maxTokens,
    }
    const data = await postJson(this.url(request), payload, { 'api-key': this.apiKey }, this.timeoutSeconds)
    const choices = (data['choices'] as Array<Record<string, unknown>>) ?? []
    const message = (choices[0]?.['message'] as Record<string, unknown>) ?? {}
    const content = (message['content'] as string) ?? ''
    const usageData = (data['usage'] as Record<string, number>) ?? {}
    const usage = new TokenUsage(usageData['prompt_tokens'] ?? 0, usageData['completion_tokens'] ?? 0)
    return this.buildResponse(request, content, usage, (data['model'] as string) ?? request.model, started)
  }

  async healthCheck(): Promise<boolean> {
    return !!(this.apiKey && this.endpoint)
  }
}

/** Factory: build an Azure OpenAI provider adapter. */
export function azureOpenaiAdapter(options: AzureOpenAIAdapterOptions = {}): AzureOpenAIAdapter {
  return new AzureOpenAIAdapter(options)
}
