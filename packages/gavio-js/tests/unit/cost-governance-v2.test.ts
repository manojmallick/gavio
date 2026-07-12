import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Gateway } from '../../src/gateway.js'
import { BudgetExceededError } from '../../src/errors.js'
import { mockProvider } from '../../src/providers/mock.js'
import {
  InMemoryBudgetStore,
  budgetPolicyControl,
  buildCostGovernanceReport,
  evaluateBudget,
  type BudgetPolicy,
} from '../../src/interceptors/governance/index.js'

function load(name: string): { cases: Record<string, unknown>[] } {
  const url = new URL(`../../../../test-vectors/cost-governance/${name}`, import.meta.url)
  return JSON.parse(readFileSync(fileURLToPath(url), 'utf8'))
}

describe('Cost Governance v2 budget decision vectors', () => {
  for (const c of load('budget-decisions.json').cases) {
    const testCase = c as {
      id: string
      policy: BudgetPolicy
      scope: string
      currentSpendUsd: number
      requestCostUsd: number
      expected: Record<string, unknown>
    }
    it(testCase.id, () => {
      const decision = evaluateBudget({
        policy: testCase.policy,
        scope: testCase.scope,
        currentSpendUsd: testCase.currentSpendUsd,
        requestCostUsd: testCase.requestCostUsd,
      })
      for (const [key, value] of Object.entries(testCase.expected)) {
        expect((decision as unknown as Record<string, unknown>)[key]).toEqual(value)
      }
      expect(decision.policyId).toBe(testCase.policy.id)
      expect(decision.scope).toBe(testCase.scope)
    })
  }
})

describe('Cost Governance v2 reports', () => {
  for (const c of load('cost-report.json').cases) {
    const testCase = c as {
      id: string
      groupBy: string
      usageElapsedRatio: number
      policies: BudgetPolicy[]
      summaries: Parameters<typeof buildCostGovernanceReport>[0]
      expected: {
        total: Record<string, unknown>
        groups: Record<string, Record<string, unknown>>
        budgets: Array<Record<string, unknown>>
      }
    }
    it(testCase.id, () => {
      const report = buildCostGovernanceReport(testCase.summaries, {
        policies: testCase.policies,
        groupBy: testCase.groupBy,
        usageElapsedRatio: testCase.usageElapsedRatio,
      })
      for (const [key, value] of Object.entries(testCase.expected.total)) {
        expect((report.total as unknown as Record<string, unknown>)[key]).toEqual(value)
      }
      for (const [group, expected] of Object.entries(testCase.expected.groups)) {
        for (const [key, value] of Object.entries(expected)) {
          expect((report.groups?.[group] as unknown as Record<string, unknown>)[key]).toEqual(value)
        }
      }
      for (const [idx, expected] of testCase.expected.budgets.entries()) {
        for (const [key, value] of Object.entries(expected)) {
          expect((report.budgets?.[idx] as unknown as Record<string, unknown>)[key]).toEqual(value)
        }
      }
    })
  }
})

describe('budgetPolicyControl', () => {
  it('falls back from stored budget state', async () => {
    const policy: BudgetPolicy = {
      id: 'tenant-total',
      scopeType: 'tenant',
      scopeValue: 'acme',
      window: 'total',
      limitUsd: 1,
      hardLimitAction: 'fallback',
      fallbackModel: 'mock-mini',
    }
    const store = new InMemoryBudgetStore({ 'tenant:acme|total': 0.95 })
    const gateway = new Gateway({ model: 'mock' })
      .withAdapter(mockProvider({ response: 'ok' }))
      .use(budgetPolicyControl({ policy, store, estimatedRequestCostUsd: 0.1 }))

    const response = await gateway.complete({
      messages: [{ role: 'user', content: 'hello' }],
      metadata: { costDimensions: { tenant: 'acme' } },
    })

    expect(response.model).toBe('mock-mini')
    expect(store.get('tenant:acme|total')).toBeGreaterThanOrEqual(0.95)
  })

  it('blocks when the policy requires block', async () => {
    const policy: BudgetPolicy = {
      id: 'tenant-total',
      scopeType: 'tenant',
      scopeValue: 'acme',
      window: 'total',
      limitUsd: 1,
      hardLimitAction: 'block',
    }
    const store = new InMemoryBudgetStore({ 'tenant:acme|total': 0.95 })
    const gateway = new Gateway({ model: 'mock' })
      .withAdapter(mockProvider({ response: 'ok' }))
      .use(budgetPolicyControl({ policy, store, estimatedRequestCostUsd: 0.1 }))

    await expect(gateway.complete({
      messages: [{ role: 'user', content: 'hello' }],
      metadata: { costDimensions: { tenant: 'acme' } },
    })).rejects.toBeInstanceOf(BudgetExceededError)
  })
})
