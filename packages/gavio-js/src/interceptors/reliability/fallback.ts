/** fallbackChain (F-REL-02) — route to a secondary provider on failure. */

import type { InterceptorContext } from '../../context.js'
import { ProviderError } from '../../errors.js'
import type { ProviderAdapter } from '../../providers/base.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import { coerceProvider } from '../../types.js'
import type { Executor, ExecutorPolicy } from '../base.js'

export interface FallbackChainOptions {
  /** Provider adapters to try, in order, when the primary call fails. */
  fallbacks: ProviderAdapter[]
}

/**
 * Try the primary executor; on a provider error, try fallback adapters.
 *
 * Each fallback is a provider adapter (anything with an async `complete`). The
 * request's provider/model are rewritten per fallback so the audit record
 * reflects which provider actually answered.
 */
class FallbackChain implements ExecutorPolicy {
  readonly name = 'fallback'
  readonly isExecutorPolicy = true as const
  readonly dryRunSafe = true

  private readonly fallbacks: ProviderAdapter[]

  constructor(options: FallbackChainOptions) {
    if (!options.fallbacks || options.fallbacks.length === 0) {
      throw new Error('fallbackChain requires at least one fallback adapter')
    }
    this.fallbacks = options.fallbacks
  }

  async around(
    request: GavioRequest,
    ctx: InterceptorContext,
    callNext: Executor,
  ): Promise<GavioResponse> {
    ctx.markFired(this.name)
    try {
      return await callNext(request)
    } catch (primaryError) {
      if (!(primaryError instanceof ProviderError)) throw primaryError
      let lastError: unknown = primaryError
      for (const adapter of this.fallbacks) {
        try {
          const rerouted = request.copyWithMessages(request.messages)
          rerouted.provider = coerceProvider(adapter.providerName)
          return await adapter.complete(rerouted)
        } catch (error) {
          if (!(error instanceof ProviderError)) throw error
          lastError = error
        }
      }
      throw lastError
    }
  }
}

/** Factory: build a fallback policy. */
export function fallbackChain(options: FallbackChainOptions): ExecutorPolicy {
  return new FallbackChain(options)
}
