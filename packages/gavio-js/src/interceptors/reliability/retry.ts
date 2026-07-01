/** retryInterceptor (F-REL-01) — exponential backoff with jitter. */

import { randomBytes } from 'node:crypto'
import type { InterceptorContext } from '../../context.js'
import {
  ProviderUnavailableError,
  RateLimitError,
  ServerError,
  TimeoutError,
} from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Executor, ExecutorPolicy } from '../base.js'

/** Default predicate: retry transient provider errors. */
function defaultRetryable(error: unknown): boolean {
  return (
    error instanceof RateLimitError ||
    error instanceof TimeoutError ||
    error instanceof ServerError ||
    error instanceof ProviderUnavailableError
  )
}

export interface RetryInterceptorOptions {
  maxAttempts?: number
  baseDelayMs?: number
  maxDelayMs?: number
  jitter?: boolean
  retryOn?: (error: unknown) => boolean
  /** Override the sleep implementation (used in tests). */
  sleep?: (ms: number) => Promise<void>
}

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms))

class RetryInterceptor implements ExecutorPolicy {
  readonly name = 'retry'
  readonly isExecutorPolicy = true as const
  readonly dryRunSafe = true

  private readonly maxAttempts: number
  private readonly baseDelayMs: number
  private readonly maxDelayMs: number
  private readonly jitter: boolean
  private readonly retryOn: (error: unknown) => boolean
  private readonly sleep: (ms: number) => Promise<void>

  constructor(options: RetryInterceptorOptions = {}) {
    const maxAttempts = options.maxAttempts ?? 3
    if (maxAttempts < 1) throw new Error('maxAttempts must be >= 1')
    this.maxAttempts = maxAttempts
    this.baseDelayMs = options.baseDelayMs ?? 500
    this.maxDelayMs = options.maxDelayMs ?? 10_000
    this.jitter = options.jitter ?? true
    this.retryOn = options.retryOn ?? defaultRetryable
    this.sleep = options.sleep ?? defaultSleep
  }

  async around(
    request: GavioRequest,
    ctx: InterceptorContext,
    callNext: Executor,
  ): Promise<GavioResponse> {
    ctx.markFired(this.name)
    let lastError: unknown
    for (let attempt = 1; attempt <= this.maxAttempts; attempt++) {
      try {
        return await callNext(request)
      } catch (error) {
        if (!this.retryOn(error)) throw error
        lastError = error
        if (attempt >= this.maxAttempts) break
        await this.sleep(this.delayMs(attempt))
      }
    }
    throw lastError
  }

  private delayMs(attempt: number): number {
    // Exponential: base * 2^(attempt-1), capped, with optional full jitter.
    const raw = this.baseDelayMs * 2 ** (attempt - 1)
    let capped = Math.min(raw, this.maxDelayMs)
    if (this.jitter) {
      // full jitter in [0, capped] using crypto bytes (no global RNG state)
      const frac = randomBytes(2).readUInt16BE(0) / 0xffff
      capped *= frac
    }
    return capped
  }
}

/** Factory: build a retry policy. */
export function retryInterceptor(options: RetryInterceptorOptions = {}): ExecutorPolicy {
  return new RetryInterceptor(options)
}
