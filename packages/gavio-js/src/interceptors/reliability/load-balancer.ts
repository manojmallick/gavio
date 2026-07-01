/** loadBalancer (F-REL-04) — weighted round-robin across provider adapters. */

import type { InterceptorContext } from '../../context.js'
import type { ProviderAdapter } from '../../providers/base.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import { coerceProvider } from '../../types.js'
import type { Executor, ExecutorPolicy } from '../base.js'

export interface LoadBalancerOptions {
  weights?: number[]
}

class LoadBalancer implements ExecutorPolicy {
  readonly name = 'load_balancer'
  readonly isExecutorPolicy = true as const

  private readonly pool: ProviderAdapter[]
  private index = 0

  constructor(adapters: ProviderAdapter[], options: LoadBalancerOptions = {}) {
    if (adapters.length === 0) {
      throw new Error('loadBalancer requires at least one adapter')
    }
    const weights = options.weights ?? adapters.map(() => 1)
    if (weights.length !== adapters.length) {
      throw new Error('weights must match adapters length')
    }
    // Expand by weight, then cycle for round-robin.
    this.pool = []
    adapters.forEach((adapter, i) => {
      for (let k = 0; k < Math.max(1, weights[i]!); k++) this.pool.push(adapter)
    })
  }

  async around(
    request: GavioRequest,
    ctx: InterceptorContext,
    _callNext: Executor,
  ): Promise<GavioResponse> {
    ctx.markFired(this.name)
    const adapter = this.pool[this.index % this.pool.length]!
    this.index += 1
    const rerouted = request.copyWithMessages(request.messages)
    rerouted.provider = coerceProvider(adapter.providerName)
    return adapter.complete(rerouted)
  }
}

/** Factory: build a load balancer over a pool of adapters. */
export function loadBalancer(
  adapters: ProviderAdapter[],
  options: LoadBalancerOptions = {},
): ExecutorPolicy {
  return new LoadBalancer(adapters, options)
}
