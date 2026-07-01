/** circuitBreaker (F-REL-03) — open/half-open/closed state machine. */

import type { InterceptorContext } from '../../context.js'
import { CircuitOpenError, ProviderError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Executor, ExecutorPolicy } from '../base.js'

export const CircuitState = {
  CLOSED: 'closed',
  OPEN: 'open',
  HALF_OPEN: 'half_open',
} as const
export type CircuitState = (typeof CircuitState)[keyof typeof CircuitState]

export interface CircuitBreakerOptions {
  failureThreshold?: number
  recoveryTimeoutSeconds?: number
  halfOpenMaxCalls?: number
}

class CircuitBreaker implements ExecutorPolicy {
  readonly name = 'circuit_breaker'
  readonly isExecutorPolicy = true as const

  private state: CircuitState = CircuitState.CLOSED
  private failures = 0
  private openedAt = 0
  private halfOpenCalls = 0

  private readonly failureThreshold: number
  private readonly recoveryMs: number
  private readonly halfOpenMaxCalls: number

  constructor(options: CircuitBreakerOptions = {}) {
    this.failureThreshold = options.failureThreshold ?? 5
    this.recoveryMs = (options.recoveryTimeoutSeconds ?? 30) * 1000
    this.halfOpenMaxCalls = options.halfOpenMaxCalls ?? 2
  }

  get currentState(): CircuitState {
    return this.state
  }

  async around(
    request: GavioRequest,
    ctx: InterceptorContext,
    callNext: Executor,
  ): Promise<GavioResponse> {
    ctx.markFired(this.name)
    this.admit() // throws CircuitOpenError if not allowed through
    try {
      const response = await callNext(request)
      this.onSuccess()
      return response
    } catch (error) {
      if (error instanceof ProviderError) this.onFailure()
      throw error
    }
  }

  private admit(): void {
    if (this.state === CircuitState.OPEN) {
      if (Date.now() - this.openedAt >= this.recoveryMs) {
        this.state = CircuitState.HALF_OPEN
        this.halfOpenCalls = 0
      } else {
        throw new CircuitOpenError('circuit is open')
      }
    }
    if (this.state === CircuitState.HALF_OPEN) {
      if (this.halfOpenCalls >= this.halfOpenMaxCalls) {
        throw new CircuitOpenError('circuit half-open probe limit reached')
      }
      this.halfOpenCalls += 1
    }
  }

  private onSuccess(): void {
    this.state = CircuitState.CLOSED
    this.failures = 0
  }

  private onFailure(): void {
    if (this.state === CircuitState.HALF_OPEN) {
      this.trip()
      return
    }
    this.failures += 1
    if (this.failures >= this.failureThreshold) this.trip()
  }

  private trip(): void {
    this.state = CircuitState.OPEN
    this.openedAt = Date.now()
  }
}

/** Factory: build a circuit breaker. */
export function circuitBreaker(options: CircuitBreakerOptions = {}): ExecutorPolicy {
  return new CircuitBreaker(options)
}
