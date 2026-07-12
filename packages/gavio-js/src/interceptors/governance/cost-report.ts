/** Cost Governance v2 report helpers. */

import { buildCostReport, type SummaryLike } from '../../inspector/analytics.js'
import type { BudgetPolicy } from './budget-v2.js'

export interface BudgetRollup {
  policyId: string
  scope: string
  window: string
  limitUsd: number
  currentSpendUsd: number
  remainingUsd: number
  forecastWindowSpendUsd: number
  status: 'ok' | 'soft_limit' | 'hard_limit'
}

export interface CostGovernanceReportOptions {
  policies?: BudgetPolicy[]
  groupBy?: string
  since?: string
  usageElapsedRatio?: number
}

export function buildCostGovernanceReport(
  summaries: SummaryLike[],
  options: CostGovernanceReportOptions = {},
): ReturnType<typeof buildCostReport> & { budgets?: BudgetRollup[] } {
  const report = buildCostReport(summaries, options.groupBy, options.since) as ReturnType<typeof buildCostReport> & {
    budgets?: BudgetRollup[]
  }
  if (options.policies === undefined || options.policies.length === 0) return report
  const ratio = options.usageElapsedRatio !== undefined && options.usageElapsedRatio > 0
    ? options.usageElapsedRatio
    : 1
  const budgets: BudgetRollup[] = []
  for (const policy of options.policies) {
    const current = spendForPolicy(report, summaries, policy, options.groupBy)
    const forecast = round8(current / ratio)
    const rollup: BudgetRollup = {
      policyId: policy.id,
      scope: reportScope(policy),
      window: policy.window,
      limitUsd: policy.limitUsd,
      currentSpendUsd: current,
      remainingUsd: round8(Math.max(policy.limitUsd - current, 0)),
      forecastWindowSpendUsd: forecast,
      status: budgetStatus(policy, current, forecast),
    }
    budgets.push(rollup)
    attachGroupBudget(report, policy, options.groupBy, rollup)
  }
  report.budgets = budgets
  return report
}

function spendForPolicy(
  report: ReturnType<typeof buildCostReport>,
  summaries: SummaryLike[],
  policy: BudgetPolicy,
  groupBy?: string,
): number {
  if (policy.scopeType === 'global') return round8(report.total.costUsd)
  if (groupBy === groupByName(policy.scopeType) && policy.scopeValue != null) {
    const group = report.groups?.[policy.scopeValue]
    if (group !== undefined) return round8(group.costUsd)
  }
  return round8(
    summaries
      .filter((summary) => summaryMatchesPolicy(summary, policy))
      .reduce((sum, summary) => sum + (summary.costUsd ?? 0), 0),
  )
}

function attachGroupBudget(
  report: ReturnType<typeof buildCostReport>,
  policy: BudgetPolicy,
  groupBy: string | undefined,
  rollup: BudgetRollup,
): void {
  if (policy.scopeValue == null || groupBy !== groupByName(policy.scopeType)) return
  const group = report.groups?.[policy.scopeValue] as Record<string, unknown> | undefined
  if (group === undefined) return
  group['budgetLimitUsd'] = rollup.limitUsd
  group['budgetRemainingUsd'] = rollup.remainingUsd
  group['forecastWindowSpendUsd'] = rollup.forecastWindowSpendUsd
}

function summaryMatchesPolicy(summary: SummaryLike, policy: BudgetPolicy): boolean {
  if (policy.scopeType === 'global') return true
  if (policy.scopeValue == null) return false
  const direct = (summary as unknown as Record<string, unknown>)[summaryField(policy.scopeType)]
  const nested = summary.costDimensions?.[groupByName(policy.scopeType)]
  const value = direct ?? nested
  return String(value) === policy.scopeValue
}

function budgetStatus(policy: BudgetPolicy, current: number, forecast: number): BudgetRollup['status'] {
  const soft = policy.limitUsd * (policy.softLimitRatio ?? 0.8)
  if (current >= policy.limitUsd) return 'hard_limit'
  if (current >= soft || forecast >= soft) return 'soft_limit'
  return 'ok'
}

function reportScope(policy: BudgetPolicy): string {
  if (policy.scopeType === 'global') return 'global'
  return `${policy.scopeType}:${policy.scopeValue ?? 'unknown'}`
}

function groupByName(scopeType: string): string {
  if (scopeType === 'agent') return 'agent_id'
  if (scopeType === 'session') return 'session_id'
  return scopeType
}

function summaryField(scopeType: string): string {
  if (scopeType === 'agent') return 'agentId'
  if (scopeType === 'session') return 'sessionId'
  if (scopeType === 'middleware_chain') return 'middlewareChain'
  return scopeType
}

function round8(value: number): number {
  return Math.round(value * 1e8) / 1e8
}
