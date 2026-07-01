/** costControl (F-GOV-02) — soft/hard budget caps per scope and window. */

import type { InterceptorContext } from '../../context.js'
import { BudgetExceededError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Interceptor } from '../base.js'

export type Scope = 'agent' | 'session' | 'global'
export type Window = 'day' | 'month' | 'total'

export interface CostControlOptions {
  hardCapUsd: number
  softCapUsd?: number
  scope?: Scope
  window?: Window
}

function scopeKey(scope: Scope, ctx: InterceptorContext): string {
  if (scope === 'agent') return `agent:${ctx.agentId ?? 'unknown'}`
  if (scope === 'session') return `session:${ctx.sessionId ?? 'unknown'}`
  return 'global'
}

function windowBucket(window: Window): string {
  const now = new Date().toISOString()
  if (window === 'day') return now.slice(0, 10)
  if (window === 'month') return now.slice(0, 7)
  return 'total'
}

export function costControl(options: CostControlOptions): Interceptor {
  const { hardCapUsd, softCapUsd, scope = 'global', window = 'day' } = options
  const spend = new Map<string, number>()
  const key = (ctx: InterceptorContext) => `${scopeKey(scope, ctx)}|${windowBucket(window)}`

  return {
    name: 'cost_control',
    before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
      const spent = spend.get(key(ctx)) ?? 0
      if (spent >= hardCapUsd) {
        throw new BudgetExceededError(
          `budget hard cap $${hardCapUsd.toFixed(2)} reached (spent $${spent.toFixed(4)})`,
        )
      }
      return request
    },
    after(response: GavioResponse, ctx: InterceptorContext): GavioResponse {
      const k = key(ctx)
      const total = (spend.get(k) ?? 0) + response.costUsd
      spend.set(k, total)
      if (softCapUsd !== undefined && total >= softCapUsd) {
        // eslint-disable-next-line no-console
        console.warn(`[gavio:budget] soft cap: $${total.toFixed(4)} of $${softCapUsd} for ${k}`)
      }
      return response
    },
  }
}
