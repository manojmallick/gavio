/** InterceptorChain — runs the pre/post pipeline around the provider call. */

import type { InterceptorContext } from '../context.js'
import type { TraceEmitter } from '../inspector/emitter.js'
import type { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import type { Executor, Interceptor } from './base.js'

const dryRunSafe = (i: Interceptor): boolean => i.dryRunSafe !== false

/** Where the chain currently is — used to classify trace.error origin. */
type Phase = { kind: 'chain' } | { kind: 'interceptor'; name: string } | { kind: 'provider' }

/**
 * Ordered list of interceptors wrapping an executor.
 *
 * `before` hooks fire in order; the executor runs; `after` hooks fire in
 * reverse order (onion model). If any stage throws, every interceptor's
 * `onError` is invoked before the error propagates.
 *
 * When an inspector {@link TraceEmitter} is supplied, the chain emits
 * interceptor.* and trace.error events. Provider calls are instrumented by the
 * gateway at the innermost executor so retry/fallback attempts each get their
 * own provider.call.* pair.
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
    emitter?: TraceEmitter,
  ): Promise<GavioResponse> {
    let phase: Phase = { kind: 'chain' }
    try {
      let req = request
      for (const interceptor of this.interceptors) {
        if (ctx.dryRun && !dryRunSafe(interceptor)) continue
        if (interceptor.before) {
          phase = { kind: 'interceptor', name: interceptor.name }
          emitter?.interceptorStart('before', interceptor.name)
          const startedAt = emitter?.now()
          const entering = req
          req = await interceptor.before(req, ctx)
          emitter?.interceptorBeforeEnd(
            interceptor.name,
            startedAt!,
            entering,
            req,
            decisionFor(ctx, interceptor.name, emitter),
          )
          emitGovernanceEvents(ctx, emitter)
          phase = { kind: 'chain' }
        }
        ctx.markFired(interceptor.name)
      }

      phase = { kind: 'provider' }
      let response: GavioResponse
      try {
        response = await executor(req)
      } catch (error) {
        throw error
      }
      phase = { kind: 'chain' }

      for (let i = this.interceptors.length - 1; i >= 0; i--) {
        const interceptor = this.interceptors[i]!
        if (ctx.dryRun && !dryRunSafe(interceptor)) continue
        if (interceptor.after) {
          phase = { kind: 'interceptor', name: interceptor.name }
          emitter?.interceptorStart('after', interceptor.name)
          const startedAt = emitter?.now()
          const entering = response
          response = await interceptor.after(response, ctx)
          emitter?.interceptorAfterEnd(
            interceptor.name,
            startedAt!,
            entering,
            response,
            decisionFor(ctx, interceptor.name, emitter),
          )
          emitGovernanceEvents(ctx, emitter)
          phase = { kind: 'chain' }
        }
      }

      response.interceptorsFired = [...ctx.interceptorsFired]
      return response
    } catch (error) {
      if (emitter !== undefined) {
        emitGovernanceEvents(ctx, emitter)
        emitter.traceError(
          phase.kind === 'interceptor' ? 'interceptor' : phase.kind === 'provider' ? 'provider' : 'chain',
          error,
          phase.kind === 'interceptor' ? phase.name : undefined,
        )
      }
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

/**
 * Decision record for an interceptor.*.end event: whatever the interceptor
 * recorded via `ctx.inspect(...)` during the hook; falls back to a state entry
 * keyed by the interceptor's own name (e.g. `ctx.state['cost_router']`).
 */
/** Drain governance events an interceptor recorded this hook and emit each. */
function emitGovernanceEvents(ctx: InterceptorContext, emitter: TraceEmitter | undefined): void {
  if (emitter === undefined) return
  for (const data of ctx.drainGovernanceEvents()) emitter.governanceEvent(data)
}

function decisionFor(
  ctx: InterceptorContext,
  name: string,
  emitter: TraceEmitter | undefined,
): Record<string, unknown> | undefined {
  if (emitter === undefined) return undefined
  const entries = ctx.drainInspectEntries()
  if (entries !== undefined) return entries
  if (!Object.prototype.hasOwnProperty.call(ctx.state, name)) return undefined
  const value = ctx.state[name]
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    return value as Record<string, unknown>
  }
  return { [name]: value }
}
