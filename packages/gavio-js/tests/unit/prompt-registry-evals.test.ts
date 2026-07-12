import { describe, it, expect } from 'vitest'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  EvalSuite,
  PromptRegistry,
  PromptTemplate,
  diffPromptTemplates,
  signPromptManifest,
  verifyPromptManifestSignature,
} from '../../src/prompts/index.js'
import type { PromptManifest } from '../../src/prompts/index.js'

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

interface RegistryV2Vector {
  registryId: string
  metadata: Record<string, unknown>
  signatureSecret: string
  manifest: PromptManifest
  expected: {
    latestVersion: string
    caretVersion: string
    tildeVersion: string
    rangeVersion: string
    renderedMessage: string
    diffPaths: string[]
    contentKeys: string[]
  }
}

const url = new URL('../../../../test-vectors/prompts/registry-evals.json', import.meta.url)
const vectors = JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) as Vector
const v2Url = new URL('../../../../test-vectors/prompts/registry-v2.json', import.meta.url)
const v2 = JSON.parse(readFileSync(fileURLToPath(v2Url), 'utf8')) as RegistryV2Vector

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

describe('Prompt Registry v2 vectors', () => {
  it('loads signed manifest files and resolves semantic version selectors', () => {
    const dir = mkdtempSync(join(tmpdir(), 'gavio-prompts-'))
    const manifestPath = join(dir, 'prompts.json')
    writeFileSync(manifestPath, JSON.stringify(v2.manifest), 'utf8')

    expect(verifyPromptManifestSignature(v2.manifest, v2.signatureSecret)).toBe(true)

    const registry = PromptRegistry.fromFile(manifestPath, { verifySecret: v2.signatureSecret })

    expect(registry.get('support.reply').version).toBe(v2.expected.latestVersion)
    expect(registry.get('support.reply', '^1.0.0').version).toBe(v2.expected.caretVersion)
    expect(registry.get('support.reply', '~1.1.0').version).toBe(v2.expected.tildeVersion)
    expect(registry.get('support.reply', '>=1.0.0 <2.0.0').version).toBe(v2.expected.rangeVersion)
    expect(registry.get('support.reply').approval?.status).toBe('pending')

    const rendered = registry.render('support.reply', {
      customerName: 'Avery',
      topic: 'refund status',
      orderId: 'A-100',
    })
    expect(rendered.messages.at(-1)?.content).toBe(v2.expected.renderedMessage)

    const roundTrip = registry.toManifest({
      registryId: v2.registryId,
      metadata: v2.metadata,
      signSecret: v2.signatureSecret,
      keyId: v2.manifest.signature?.keyId,
    })
    expect(roundTrip.signature?.value).toBe(v2.manifest.signature?.value)
  })

  it('reports metadata-safe prompt diffs', () => {
    const templates = v2.manifest.templates.map((template) => new PromptTemplate(template))

    const diff = diffPromptTemplates(templates[0]!, templates[1]!)

    expect(diff.changes.map((change) => change.path)).toEqual(v2.expected.diffPaths)
    expect(diff.changes
      .filter((change) => change.path.startsWith('messages['))
      .every((change) => change.beforeHash !== undefined || change.afterHash !== undefined)).toBe(true)
    const serialized = JSON.stringify(diff)
    for (const content of v2.expected.contentKeys) expect(serialized).not.toContain(content)
  })

  it('rejects invalid semver manifests and signs deterministically', () => {
    const badManifest: PromptManifest = {
      ...v2.manifest,
      templates: [{ ...v2.manifest.templates[0]!, version: '2026-07-12' }],
    }

    expect(() => PromptRegistry.fromManifest(badManifest, { validateSemver: true })).toThrow(/semantic version/)

    const unsigned = { ...v2.manifest }
    delete unsigned.signature
    const signed = signPromptManifest(unsigned, v2.signatureSecret, v2.manifest.signature?.keyId)

    expect(signed.signature).toEqual(v2.manifest.signature)
  })
})
