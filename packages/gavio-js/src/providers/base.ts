/** ProviderAdapter interface and shared response-building helpers. */

import { PricingProvider } from '../pricing.js'
import { GavioRequest } from '../request.js'
import { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'

/** Adapter to one LLM provider. */
export interface ProviderAdapter {
  readonly providerName: string
  complete(request: GavioRequest): Promise<GavioResponse>
  stream?(request: GavioRequest): AsyncIterable<string>
  healthCheck(): Promise<boolean>
  readonly reportedModelVersion?: string | null
}

/** Base class with a shared pricing provider and response builder. */
export abstract class BaseProviderAdapter implements ProviderAdapter {
  protected readonly pricing: PricingProvider

  constructor(pricing?: PricingProvider) {
    this.pricing = pricing ?? new PricingProvider()
  }

  abstract get providerName(): string
  abstract complete(request: GavioRequest): Promise<GavioResponse>
  abstract healthCheck(): Promise<boolean>

  get reportedModelVersion(): string | null {
    return null
  }

  protected buildResponse(
    request: GavioRequest,
    content: string,
    usage: TokenUsage,
    modelVersion: string,
    startedAt: number,
  ): GavioResponse {
    const latencyMs = Math.floor(performance.now() - startedAt)
    return new GavioResponse({
      traceId: request.traceId,
      content,
      model: request.model,
      provider: this.providerName,
      modelVersion: modelVersion || request.model,
      usage,
      costUsd: this.pricing.estimate(request.model, usage),
      latencyMs,
    })
  }
}
