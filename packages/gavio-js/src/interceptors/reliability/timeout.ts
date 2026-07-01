/** timeoutPolicy (F-REL-07) — per-request timeout enforcement. */

import type { InterceptorContext } from '../../context.js'
import { TimeoutError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Executor, ExecutorPolicy } from '../base.js'

export interface TimeoutPolicyOptions {
  timeoutSeconds?: number
}

class TimeoutPolicy implements ExecutorPolicy {
  readonly name = 'timeout'
  readonly isExecutorPolicy = true as const
  readonly dryRunSafe = true

  private readonly timeoutSeconds: number

  constructor(options: TimeoutPolicyOptions = {}) {
    const timeoutSeconds = options.timeoutSeconds ?? 30.0
    if (timeoutSeconds <= 0) throw new Error('timeoutSeconds must be > 0')
    this.timeoutSeconds = timeoutSeconds
  }

  async around(
    request: GavioRequest,
    ctx: InterceptorContext,
    callNext: Executor,
  ): Promise<GavioResponse> {
    ctx.markFired(this.name)
    const ms = this.timeoutSeconds * 1000
    let timer: ReturnType<typeof setTimeout> | undefined
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => {
        reject(new TimeoutError(`Request exceeded ${this.timeoutSeconds}s timeout`))
      }, ms)
    })
    try {
      return await Promise.race([callNext(request), timeout])
    } finally {
      if (timer !== undefined) clearTimeout(timer)
    }
  }
}

/** Factory: build a timeout policy. */
export function timeoutPolicy(options: TimeoutPolicyOptions = {}): ExecutorPolicy {
  return new TimeoutPolicy(options)
}

/** Alias matching the SDK plan naming (`timeout`). */
export const timeout = timeoutPolicy
