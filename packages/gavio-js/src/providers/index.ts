/** Provider adapters and the provider registry. */

import { ConfigurationError } from '../errors.js'
import type { PricingProvider } from '../pricing.js'
import { Provider, coerceProvider } from '../types.js'
import { anthropicAdapter } from './anthropic.js'
import type { ProviderAdapter } from './base.js'
import { mockProvider } from './mock.js'
import { openaiAdapter } from './openai.js'

export type { ProviderAdapter } from './base.js'
export { BaseProviderAdapter } from './base.js'
export { mockProvider } from './mock.js'
export type { MockProviderOptions } from './mock.js'
export { openaiAdapter } from './openai.js'
export type { OpenAIAdapterOptions } from './openai.js'
export { anthropicAdapter } from './anthropic.js'
export type { AnthropicAdapterOptions } from './anthropic.js'
export { Provider } from '../types.js'

/** Instantiate the default adapter for a provider id. v0.1.0: OpenAI, Anthropic, Mock. */
export function buildAdapter(
  provider: Provider | string,
  pricing?: PricingProvider,
): ProviderAdapter {
  const p = coerceProvider(provider)
  switch (p) {
    case Provider.OPENAI:
      return openaiAdapter(pricing ? { pricing } : {})
    case Provider.ANTHROPIC:
      return anthropicAdapter(pricing ? { pricing } : {})
    case Provider.MOCK:
      return mockProvider(pricing ? { pricing } : {})
    default:
      throw new ConfigurationError(
        `Provider '${p}' is not available in v0.1.0 (available: openai, anthropic, mock)`,
      )
  }
}
