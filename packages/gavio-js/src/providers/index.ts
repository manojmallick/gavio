/** Provider adapters and the provider registry. */

import { ConfigurationError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import { Provider, coerceProvider } from '../types.js'
import { anthropicAdapter } from './anthropic.js'
import { azureOpenaiAdapter } from './azure-openai.js'
import type { ProviderAdapter } from './base.js'
import { geminiAdapter } from './gemini.js'
import { mockProvider } from './mock.js'
import { ollamaAdapter } from './ollama.js'
import { openaiAdapter } from './openai.js'

export type { ProviderAdapter } from './base.js'
export { BaseProviderAdapter } from './base.js'
export { mockProvider } from './mock.js'
export type { MockProviderOptions } from './mock.js'
export { openaiAdapter } from './openai.js'
export type { OpenAIAdapterOptions } from './openai.js'
export { anthropicAdapter } from './anthropic.js'
export type { AnthropicAdapterOptions } from './anthropic.js'
export { geminiAdapter } from './gemini.js'
export type { GeminiAdapterOptions } from './gemini.js'
export { azureOpenaiAdapter } from './azure-openai.js'
export type { AzureOpenAIAdapterOptions } from './azure-openai.js'
export { ollamaAdapter } from './ollama.js'
export type { OllamaAdapterOptions } from './ollama.js'
export { Provider } from '../types.js'

/** Instantiate the default adapter for a provider id. */
export function buildAdapter(
  provider: Provider | string,
  pricing?: PricingProvider,
): ProviderAdapter {
  const p = coerceProvider(provider)
  const opts = pricing ? { pricing } : {}
  switch (p) {
    case Provider.OPENAI:
      return openaiAdapter(opts)
    case Provider.ANTHROPIC:
      return anthropicAdapter(opts)
    case Provider.GEMINI:
      return geminiAdapter(opts)
    case Provider.AZURE_OPENAI:
      return azureOpenaiAdapter(opts)
    case Provider.OLLAMA:
      return ollamaAdapter(opts)
    case Provider.MOCK:
      return mockProvider(opts)
    default:
      throw new ConfigurationError(
        `Provider '${p}' is not available (v0.3.0 adds bedrock, cohere)`,
      )
  }
}
