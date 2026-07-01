/** The Interceptor interface — the unit of composition in Gavio. */

import type { InterceptorContext } from '../context.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'

/**
 * A pre/post hook around the provider call.
 *
 * `before` runs in registration order on the request; `after` runs in reverse
 * order on the response (onion model). Either may be omitted. Throwing from
 * `before` aborts the call.
 */
export interface Interceptor {
  /** Unique name used in audit logs and metrics. */
  readonly name: string

  /** Pre-interceptor: runs before the provider call. */
  before?(
    request: GavioRequest,
    ctx: InterceptorContext,
  ): Promise<GavioRequest> | GavioRequest

  /** Post-interceptor: runs after the provider call. */
  after?(
    response: GavioResponse,
    ctx: InterceptorContext,
  ): Promise<GavioResponse> | GavioResponse

  /** Called if the provider call or a downstream interceptor throws. */
  onError?(error: Error, ctx: InterceptorContext): void | Promise<void>

  /** If true (default), participates in dry-run mode (logs only). */
  readonly dryRunSafe?: boolean
}

/** A function that takes the final request and returns a response (the provider call). */
export type Executor = (request: GavioRequest) => Promise<GavioResponse>

/**
 * Base class for executor-wrapping reliability policies.
 *
 * Retry, timeout, and fallback can't be expressed as plain before/after hooks:
 * they need to re-invoke (or race) the executor. They implement `around` so the
 * Gateway composes them *around* the provider call, innermost-last.
 */
export interface ExecutorPolicy extends Interceptor {
  readonly isExecutorPolicy: true
  around(
    request: GavioRequest,
    ctx: InterceptorContext,
    callNext: Executor,
  ): Promise<GavioResponse>
}

export function isExecutorPolicy(i: Interceptor): i is ExecutorPolicy {
  return (i as Partial<ExecutorPolicy>).isExecutorPolicy === true
}
