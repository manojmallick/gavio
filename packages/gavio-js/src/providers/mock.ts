/** mockProvider — deterministic, offline provider for dev mode and tests. */

import { createHash } from 'node:crypto'

import { PricingProvider, estimateTokens } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { TokenUsage } from '../types.js'
import { BaseProviderAdapter } from './base.js'

export interface MockProviderOptions {
  response?: string | null
  modelVersion?: string
  pricing?: PricingProvider
}

/**
 * Returns a canned response without any network call.
 *
 * If `response` is null/undefined, it echoes the last user message so the
 * pipeline (including PII restore) is observable end to end.
 */
class MockProvider extends BaseProviderAdapter {
  private readonly response: string | null
  private readonly modelVersion: string

  constructor(options: MockProviderOptions = {}) {
    super(options.pricing)
    this.response = options.response ?? null
    this.modelVersion = options.modelVersion ?? 'mock-1'
  }

  get providerName(): string {
    return 'mock'
  }

  override get reportedModelVersion(): string | null {
    return this.modelVersion
  }

  private contentFor(request: GavioRequest): string {
    if (this.response !== null) return this.response
    const lastUser = [...request.messages]
      .reverse()
      .find((m) => m.role === 'user')
    return `[mock reply] ${lastUser?.content ?? ''}`
  }

  async complete(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const content = this.contentFor(request)
    const usage = new TokenUsage(
      estimateTokens(request.promptText()),
      estimateTokens(content),
    )
    return this.buildResponse(request, content, usage, this.modelVersion, started)
  }

  /** Deterministic 8-dim vector per message content (F-SEC-10). */
  async embed(request: GavioRequest): Promise<GavioResponse> {
    const started = performance.now()
    const vectors = request.messages.map((m) => mockVector(m.content ?? ''))
    const usage = new TokenUsage(estimateTokens(request.promptText()))
    return this.buildEmbedResponse(request, vectors, usage, this.modelVersion, started)
  }

  async *stream(request: GavioRequest): AsyncIterable<string> {
    for (const token of this.contentFor(request).split(' ')) {
      yield token + ' '
    }
  }

  async healthCheck(): Promise<boolean> {
    return true
  }
}

/** Stable pseudo-embedding: sha256 bytes scaled to [0, 1). */
function mockVector(text: string, dims = 8): number[] {
  const digest = createHash('sha256').update(text, 'utf-8').digest()
  return Array.from({ length: dims }, (_, i) => digest[i]! / 255)
}

/** Factory: build a mock provider adapter. */
export function mockProvider(options: MockProviderOptions = {}): ProviderAdapterMock {
  return new MockProvider(options)
}

export type ProviderAdapterMock = MockProvider
