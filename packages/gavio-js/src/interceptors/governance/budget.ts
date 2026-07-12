/** costControl (F-GOV-02) — soft/hard budget caps per scope and window. */

import type { InterceptorContext } from '../../context.js'
import { BudgetExceededError } from '../../errors.js'
import { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Interceptor } from '../base.js'

export type Scope = 'agent' | 'session' | 'tenant' | 'feature' | 'user' | 'model' | 'global'
export type Window = 'day' | 'month' | 'total'

export interface CostControlOptions {
  hardCapUsd: number
  softCapUsd?: number
  scope?: Scope
  window?: Window
  /** Optional cheaper model used instead of blocking once the hard cap is reached. */
  fallbackModel?: string
}

const COST_CONTROL_KEY_STATE = 'cost_control:budget_key'

function scopeKey(scope: Scope, request: GavioRequest, ctx: InterceptorContext): string {
  if (scope === 'agent') return `agent:${ctx.agentId ?? 'unknown'}`
  if (scope === 'session') return `session:${ctx.sessionId ?? 'unknown'}`
  if (scope === 'model') return `model:${request.model}`
  if (scope === 'tenant') return `tenant:${dimension(request, 'tenant')}`
  if (scope === 'feature') return `feature:${dimension(request, 'feature')}`
  if (scope === 'user') return `user:${dimension(request, 'user')}`
  return 'global'
}

function windowBucket(window: Window): string {
  const now = new Date().toISOString()
  if (window === 'day') return now.slice(0, 10)
  if (window === 'month') return now.slice(0, 7)
  return 'total'
}

export function costControl(options: CostControlOptions): Interceptor {
  const { hardCapUsd, softCapUsd, scope = 'global', window = 'day', fallbackModel } = options
  const spend = new Map<string, number>()
  const key = (request: GavioRequest, ctx: InterceptorContext) =>
    `${scopeKey(scope, request, ctx)}|${windowBucket(window)}`

  return {
    name: 'cost_control',
    before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
      const budgetKey = key(request, ctx)
      ctx.state[COST_CONTROL_KEY_STATE] = budgetKey
      const spent = spend.get(budgetKey) ?? 0
      if (spent >= hardCapUsd) {
        const event = {
          kind: 'budget',
          action: fallbackModel !== undefined && request.model !== fallbackModel ? 'fallback' : 'block',
          scope,
          key: budgetKey,
          spentUsd: round4(spent),
          hardCapUsd,
        }
        ctx.inspect('budget', event)
        ctx.recordGovernanceEvent(event)
        if (fallbackModel !== undefined && request.model !== fallbackModel) {
          return copyWithModel(request, fallbackModel)
        }
        throw new BudgetExceededError(
          `budget hard cap $${hardCapUsd.toFixed(2)} reached (spent $${spent.toFixed(4)})`,
        )
      }
      return request
    },
    after(response: GavioResponse, ctx: InterceptorContext): GavioResponse {
      const k = typeof ctx.state[COST_CONTROL_KEY_STATE] === 'string'
        ? ctx.state[COST_CONTROL_KEY_STATE]
        : 'global|total'
      const total = (spend.get(k) ?? 0) + response.costUsd
      spend.set(k, total)
      if (softCapUsd !== undefined && total >= softCapUsd) {
        const event = {
          kind: 'budget',
          action: 'warn',
          scope,
          key: k,
          spentUsd: round4(total),
          softCapUsd,
        }
        ctx.inspect('budget', event)
        ctx.recordGovernanceEvent(event)
        // eslint-disable-next-line no-console
        console.warn(`[gavio:budget] soft cap: $${total.toFixed(4)} of $${softCapUsd} for ${k}`)
      }
      return response
    },
  }
}

function dimension(request: GavioRequest, key: 'tenant' | 'feature' | 'user'): string {
  const nested = request.metadata['costDimensions']
  const nestedSnake = request.metadata['cost_dimensions']
  const value = readDimension(nested, key) ?? readDimension(nestedSnake, key)
    ?? readDimension(request.metadata, key)
  return value ?? 'unknown'
}

function readDimension(source: unknown, key: 'tenant' | 'feature' | 'user'): string | undefined {
  if (source === null || typeof source !== 'object' || Array.isArray(source)) return undefined
  const aliases: Record<typeof key, string[]> = {
    tenant: ['tenant', 'tenantId', 'tenant_id'],
    feature: ['feature', 'featureId', 'feature_id'],
    user: ['user', 'userId', 'user_id'],
  }
  const object = source as Record<string, unknown>
  for (const alias of aliases[key]) {
    const value = object[alias]
    if (typeof value === 'string' && value.trim() !== '') return value.trim()
    if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  }
  return undefined
}

function copyWithModel(request: GavioRequest, model: string): GavioRequest {
  return new GavioRequest({
    messages: request.messages,
    model,
    provider: request.provider,
    traceId: request.traceId,
    agentId: request.agentId,
    parentTraceId: request.parentTraceId,
    sessionId: request.sessionId,
    options: { ...request.options },
    metadata: { ...request.metadata },
    images: request.images,
    lineage: request.lineage,
  })
}

function round4(value: number): number {
  return Math.round(value * 1e4) / 1e4
}
