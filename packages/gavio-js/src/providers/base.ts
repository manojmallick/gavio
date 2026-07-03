/** ProviderAdapter interface and shared response-building helpers. */

import { PricingProvider, estimateTokens } from '../pricing.js'
import { GavioRequest } from '../request.js'
import { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'

/** Adapter to one LLM provider. */
export interface ProviderAdapter {
  readonly providerName: string
  complete(request: GavioRequest): Promise<GavioResponse>
  stream?(request: GavioRequest): AsyncIterable<string>
  /** Build a response from a fully buffered stream (F-REL-06). */
  buildStreamResponse?(request: GavioRequest, content: string, startedAt: number): GavioResponse
  /** Embed the request's message contents (F-SEC-10). Optional per adapter. */
  embed?(request: GavioRequest): Promise<GavioResponse>
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

  /**
   * Build a response from a fully buffered stream (F-REL-06). Streamed chunks
   * carry text only, so token usage is estimated from prompt + content.
   */
  buildStreamResponse(request: GavioRequest, content: string, startedAt: number): GavioResponse {
    const usage = new TokenUsage(
      estimateTokens(request.promptText()),
      estimateTokens(content),
    )
    return this.buildResponse(
      request,
      content,
      usage,
      this.reportedModelVersion ?? request.model,
      startedAt,
    )
  }

  /** Build an embedding response — empty content, one vector per input (F-SEC-10). */
  protected buildEmbedResponse(
    request: GavioRequest,
    vectors: number[][],
    usage: TokenUsage,
    modelVersion: string,
    startedAt: number,
  ): GavioResponse {
    const response = this.buildResponse(request, '', usage, modelVersion, startedAt)
    response.embeddings = vectors
    return response
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
