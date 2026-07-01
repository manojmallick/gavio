/** InterceptorChain — runs the pre/post pipeline around the provider call. */

import type { InterceptorContext } from '../context.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import type { Executor, Interceptor } from './base.js'

const dryRunSafe = (i: Interceptor): boolean => i.dryRunSafe !== false

/**
 * Ordered list of interceptors wrapping an executor.
 *
 * `before` hooks fire in order; the executor runs; `after` hooks fire in
 * reverse order (onion model). If any stage throws, every interceptor's
 * `onError` is invoked before the error propagates.
 */
export class InterceptorChain {
  private readonly interceptors: Interceptor[]

  constructor(interceptors: Interceptor[]) {
    this.interceptors = [...interceptors]
  }

  async execute(
    request: GavioRequest,
    ctx: InterceptorContext,
    executor: Executor,
  ): Promise<GavioResponse> {
    try {
      let req = request
      for (const interceptor of this.interceptors) {
        if (ctx.dryRun && !dryRunSafe(interceptor)) continue
        if (interceptor.before) {
          req = await interceptor.before(req, ctx)
        }
        ctx.markFired(interceptor.name)
      }

      let response = await executor(req)

      for (let i = this.interceptors.length - 1; i >= 0; i--) {
        const interceptor = this.interceptors[i]!
        if (ctx.dryRun && !dryRunSafe(interceptor)) continue
        if (interceptor.after) {
          response = await interceptor.after(response, ctx)
        }
      }

      response.interceptorsFired = [...ctx.interceptorsFired]
      return response
    } catch (error) {
      const err = error instanceof Error ? error : new Error(String(error))
      for (const interceptor of this.interceptors) {
        if (interceptor.onError) {
          try {
            await interceptor.onError(err, ctx)
          } catch {
            // on_error must never break the propagation of the original error.
          }
        }
      }
      throw error
    }
  }
}
