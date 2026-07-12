/** Cost Governance v2 budget policy evaluation. */

import type { InterceptorContext } from '../../context.js'
import { BudgetExceededError } from '../../errors.js'
import { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Interceptor } from '../base.js'

export type BudgetScopeType =
  | 'global'
  | 'tenant'
  | 'team'
  | 'user'
  | 'feature'
  | 'agent'
  | 'session'
  | 'model'
  | 'request'
export type BudgetWindow = 'daily' | 'weekly' | 'monthly' | 'rolling' | 'total'
export type HardLimitAction = 'block' | 'fallback' | 'downgrade_model' | 'dry_run'
export type BudgetAction = 'allow' | 'warn' | HardLimitAction
export type ThresholdStatus = 'ok' | 'soft_limit' | 'hard_limit'

export interface BudgetPolicy {
  id: string
  scopeType: BudgetScopeType
  scopeValue?: string | null
  window: BudgetWindow
  limitUsd: number
  softLimitRatio?: number
  hardLimitAction: HardLimitAction
  alertThresholds?: number[]
  fallbackModel?: string | null
  metadata?: Record<string, unknown>
}

export interface BudgetDecision {
  policyId: string
  scope: string
  window: string
  allowed: boolean
  action: BudgetAction
  currentSpendUsd: number
  projectedSpendUsd: number
  remainingUsd: number
  thresholdStatus: ThresholdStatus
  reason: string
  targetModel?: string
  alertThresholdsCrossed: number[]
  metadata: Record<string, unknown>
}

export interface BudgetStore {
  get(scope: string): number
  add(scope: string, costUsd: number): number
}

export class InMemoryBudgetStore implements BudgetStore {
  private readonly spend = new Map<string, number>()

  constructor(initial?: Record<string, number>) {
    for (const [key, value] of Object.entries(initial ?? {})) this.spend.set(key, value)
  }

  get(scope: string): number {
    return this.spend.get(scope) ?? 0
  }

  add(scope: string, costUsd: number): number {
    const total = this.get(scope) + costUsd
    this.spend.set(scope, total)
    return total
  }
}

export interface BudgetPolicyControlOptions {
  policy: BudgetPolicy
  store?: BudgetStore
  estimatedRequestCostUsd?: number
}

const SCOPE_STATE = 'budget_v2:scope'
const DECISION_STATE = 'budget_v2:decision'

export function evaluateBudget(options: {
  policy: BudgetPolicy
  scope: string
  currentSpendUsd: number
  requestCostUsd: number
}): BudgetDecision {
  const { policy, scope } = options
  const softLimitRatio = policy.softLimitRatio ?? 0.8
  const current = round8(Math.max(options.currentSpendUsd, 0))
  const projected = round8(Math.max(options.currentSpendUsd + options.requestCostUsd, 0))
  const remaining = round8(Math.max(policy.limitUsd - projected, 0))
  const ratioBefore = ratio(current, policy.limitUsd)
  const ratioAfter = ratio(projected, policy.limitUsd)
  const crossed = [...new Set(policy.alertThresholds ?? [])]
    .sort((a, b) => a - b)
    .filter((threshold) => ratioBefore < threshold && threshold <= ratioAfter)

  if (projected > policy.limitUsd) {
    if (policy.hardLimitAction === 'fallback') {
      return decision(policy, scope, true, 'fallback', current, projected, remaining, 'hard_limit',
        'fallback_after_hard_limit', crossed, policy.fallbackModel ?? undefined)
    }
    if (policy.hardLimitAction === 'downgrade_model') {
      return decision(policy, scope, true, 'downgrade_model', current, projected, remaining,
        'hard_limit', 'downgrade_after_hard_limit', crossed, policy.fallbackModel ?? undefined)
    }
    if (policy.hardLimitAction === 'dry_run') {
      return decision(policy, scope, true, 'dry_run', current, projected, remaining, 'hard_limit',
        'hard_limit_dry_run', crossed)
    }
    return decision(policy, scope, false, 'block', current, projected, remaining, 'hard_limit',
      'hard_limit_exceeded', crossed)
  }

  if (ratioAfter >= softLimitRatio) {
    return decision(policy, scope, true, 'warn', current, projected, remaining, 'soft_limit',
      'soft_limit_exceeded', crossed)
  }

  return decision(policy, scope, true, 'allow', current, projected, remaining, 'ok',
    'under_budget', crossed)
}

export function budgetPolicyControl(options: BudgetPolicyControlOptions): Interceptor {
  const { policy, store = new InMemoryBudgetStore(), estimatedRequestCostUsd = 0 } = options
  return {
    name: 'budget_policy',
    before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
      const scope = resolvePolicyScope(policy, request, ctx)
      const budgetDecision = evaluateBudget({
        policy,
        scope,
        currentSpendUsd: store.get(scope),
        requestCostUsd: estimatedRequestCostUsd,
      })
      ctx.state[SCOPE_STATE] = scope
      ctx.state[DECISION_STATE] = budgetDecision
      ctx.inspect('budget_decision', budgetDecision)
      if (budgetDecision.thresholdStatus !== 'ok') {
        ctx.recordGovernanceEvent({ kind: 'budget', decision: budgetDecision, policyId: policy.id })
      }
      if (
        (budgetDecision.action === 'fallback' || budgetDecision.action === 'downgrade_model') &&
        budgetDecision.targetModel !== undefined &&
        request.model !== budgetDecision.targetModel
      ) {
        return copyWithModel(request, budgetDecision.targetModel)
      }
      if (!budgetDecision.allowed) {
        throw new BudgetExceededError(
          `budget policy ${policy.id} exceeded for ${scope}: ` +
          `projected $${budgetDecision.projectedSpendUsd.toFixed(4)} > $${policy.limitUsd.toFixed(4)}`,
        )
      }
      return request
    },
    after(response: GavioResponse, ctx: InterceptorContext): GavioResponse {
      const scope = ctx.state[SCOPE_STATE]
      if (typeof scope === 'string') store.add(scope, response.costUsd)
      return response
    },
  }
}

export function resolvePolicyScope(
  policy: BudgetPolicy,
  request: GavioRequest,
  ctx?: InterceptorContext,
  now = new Date(),
): string {
  const value = policy.scopeValue ?? requestScopeValue(policy.scopeType, request, ctx)
  const prefix = policy.scopeType === 'global' ? 'global' : `${policy.scopeType}:${value}`
  return `${prefix}|${windowBucket(policy.window, now)}`
}

export function windowBucket(window: string, now = new Date()): string {
  if (window === 'daily' || window === 'day') return now.toISOString().slice(0, 10)
  if (window === 'monthly' || window === 'month') return now.toISOString().slice(0, 7)
  if (window === 'weekly' || window === 'week') return isoWeekBucket(now)
  if (window === 'rolling' || window === 'total') return window
  return 'total'
}

function decision(
  policy: BudgetPolicy,
  scope: string,
  allowed: boolean,
  action: BudgetAction,
  currentSpendUsd: number,
  projectedSpendUsd: number,
  remainingUsd: number,
  thresholdStatus: ThresholdStatus,
  reason: string,
  alertThresholdsCrossed: number[],
  targetModel?: string,
): BudgetDecision {
  const out: BudgetDecision = {
    policyId: policy.id,
    scope,
    window: policy.window,
    allowed,
    action,
    currentSpendUsd,
    projectedSpendUsd,
    remainingUsd,
    thresholdStatus,
    reason,
    alertThresholdsCrossed,
    metadata: {},
  }
  if (targetModel !== undefined) out.targetModel = targetModel
  return out
}

function requestScopeValue(
  scopeType: BudgetScopeType,
  request: GavioRequest,
  ctx?: InterceptorContext,
): string {
  if (scopeType === 'global') return 'global'
  if (scopeType === 'agent') return ctx?.agentId ?? request.agentId ?? 'unknown'
  if (scopeType === 'session') return ctx?.sessionId ?? request.sessionId ?? 'unknown'
  if (scopeType === 'model') return request.model
  if (scopeType === 'request') return request.traceId
  return dimension(request.metadata, scopeType) ?? 'unknown'
}

function dimension(metadata: Record<string, unknown>, key: string): string | undefined {
  const nested = metadata['costDimensions']
  const nestedSnake = metadata['cost_dimensions']
  return readDimension(nested, key) ?? readDimension(nestedSnake, key) ?? readDimension(metadata, key)
}

function readDimension(source: unknown, key: string): string | undefined {
  if (source === null || typeof source !== 'object' || Array.isArray(source)) return undefined
  const aliases: Record<string, string[]> = {
    tenant: ['tenant', 'tenantId', 'tenant_id'],
    team: ['team', 'teamId', 'team_id'],
    feature: ['feature', 'featureId', 'feature_id'],
    user: ['user', 'userId', 'user_id'],
  }
  const object = source as Record<string, unknown>
  for (const alias of aliases[key] ?? [key]) {
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

function isoWeekBucket(date: Date): string {
  const utc = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()))
  const day = utc.getUTCDay() || 7
  utc.setUTCDate(utc.getUTCDate() + 4 - day)
  const yearStart = new Date(Date.UTC(utc.getUTCFullYear(), 0, 1))
  const week = Math.ceil((((utc.getTime() - yearStart.getTime()) / 86400000) + 1) / 7)
  return `${utc.getUTCFullYear()}-W${String(week).padStart(2, '0')}`
}

function ratio(spend: number, limit: number): number {
  if (limit <= 0) return spend > 0 ? Number.POSITIVE_INFINITY : 0
  return spend / limit
}

function round8(value: number): number {
  return Math.round(value * 1e8) / 1e8
}
