import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { EvalSuite, PromptRegistry, PromptTemplate } from '../../src/prompts/index.js'

interface Vector {
  contentKeys: string[]
  templates: Array<{
    id: string
    version: string
    messages: Array<{ role: string; content: string }>
    requiredVariables: string[]
    variables: Record<string, unknown>
    expectedMessages: Array<{ role: string; content: string }>
    expectedLineage: {
      templateId: string
      templateVersion: string
      variables: Record<string, unknown>
      ragChunks: unknown[]
    }
    missingVariables: string[]
  }>
  suite: {
    id: string
    cases: Array<{
      id: string
      templateId: string
      variables: Record<string, unknown>
      mockOutput: string
      assertions: Array<{ type: 'contains' | 'not_contains'; value: string }>
      expected: { passed: boolean; score: number }
    }>
    expectedReport: {
      suiteId: string
      totalCases: number
      passedCases: number
      failedCases: number
      score: number
    }
  }
}

const url = new URL('../../../../test-vectors/prompts/registry-evals.json', import.meta.url)
const vectors = JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) as Vector

describe('Prompt Registry vectors', () => {
  it('renders templates and keeps lineage metadata-only', () => {
    const templateVector = vectors.templates[0]!
    const registry = new PromptRegistry([new PromptTemplate(templateVector)])

    const rendered = registry.render(templateVector.id, templateVector.variables)

    expect(rendered.messages).toEqual(templateVector.expectedMessages)
    expect(rendered.lineage.toJSON()).toEqual(templateVector.expectedLineage)
    expect(rendered.lineage.toJSON()).not.toHaveProperty('renderedPrompt')
  })

  it('reports missing required variables', () => {
    const templateVector = vectors.templates[0]!
    const registry = new PromptRegistry([templateVector])
    const variables = Object.fromEntries(
      Object.entries(templateVector.variables).filter(([key]) => !templateVector.missingVariables.includes(key)),
    )

    expect(() => registry.render(templateVector.id, variables)).toThrow(/topic/)
  })
})

describe('EvalSuite vectors', () => {
  it('runs shared eval cases without storing raw output', async () => {
    const registry = new PromptRegistry(vectors.templates)
    const suite = new EvalSuite(vectors.suite)
    const outputs = new Map(vectors.suite.cases.map((testCase) => [testCase.id, testCase.mockOutput]))

    const report = await suite.run(registry, (_prompt, testCase) => outputs.get(testCase.id) ?? '')

    expect(report.suiteId).toBe(vectors.suite.expectedReport.suiteId)
    expect(report.totalCases).toBe(vectors.suite.expectedReport.totalCases)
    expect(report.passedCases).toBe(vectors.suite.expectedReport.passedCases)
    expect(report.failedCases).toBe(vectors.suite.expectedReport.failedCases)
    expect(report.score).toBe(vectors.suite.expectedReport.score)
    for (const [idx, result] of report.cases.entries()) {
      expect(result.passed).toBe(vectors.suite.cases[idx]!.expected.passed)
      expect(result.score).toBe(vectors.suite.cases[idx]!.expected.score)
      expect(result.outputHash).toMatch(/^[a-f0-9]{64}$/)
    }
    const serialized = JSON.stringify(report)
    for (const contentKey of vectors.contentKeys) expect(serialized).not.toContain(`"${contentKey}"`)
    for (const testCase of vectors.suite.cases) expect(serialized).not.toContain(testCase.mockOutput)
  })
})
