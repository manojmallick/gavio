import { describe, expect, it } from 'vitest'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  canonicalJson,
  platformWorkflowReleaseHash,
  runEvalFile,
  runPlatformWorkflowReleaseFile,
} from '../../src/index.js'

interface ReleaseCase {
  id: string
  description: string
  files: Record<string, unknown>
  manifest: Record<string, unknown>
  forbiddenStrings: string[]
  expected: { passed: boolean; reasons: string[]; artifact: Record<string, unknown> }
}

const vectors = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../test-vectors/workflow/release.json', import.meta.url)),
    'utf8',
  ),
) as {
  canonicalJsonCases: Array<{ id: string; value: unknown; expected: string }>
  hashCases: Array<{ id: string; artifact: Record<string, unknown>; expectedHash: string }>
  releaseCases: ReleaseCase[]
}

function stripEnvironment(artifact: Record<string, unknown>): Record<string, unknown> {
  const copy = JSON.parse(JSON.stringify(artifact)) as Record<string, unknown>
  for (const item of copy.evals as Array<Record<string, unknown>>) {
    delete item.runner
    delete item.source
  }
  return copy
}

describe('platform workflow release', () => {
  for (const testCase of vectors.canonicalJsonCases) {
    it(`canonical JSON: ${testCase.id}`, () => {
      expect(canonicalJson(testCase.value)).toBe(testCase.expected)
    })
  }

  for (const testCase of vectors.hashCases) {
    it(`workflow hash: ${testCase.id}`, () => {
      expect(platformWorkflowReleaseHash(testCase.artifact)).toBe(testCase.expectedHash)
    })
  }

  for (const testCase of vectors.releaseCases) {
    it(`release vector: ${testCase.id}`, async () => {
      const root = mkdtempSync(join(tmpdir(), 'gavio-workflow-'))
      try {
        for (const [relative, document] of Object.entries(testCase.files)) {
          const path = join(root, relative)
          mkdirSync(dirname(path), { recursive: true })
          writeFileSync(path, JSON.stringify(document))
        }
        writeFileSync(join(root, 'workflow.json'), JSON.stringify(testCase.manifest))

        const result = await runPlatformWorkflowReleaseFile(join(root, 'workflow.json'))

        expect(result.passed).toBe(testCase.expected.passed)
        expect(result.reasons).toEqual(testCase.expected.reasons)
        const artifact = stripEnvironment(result.artifact)
        expect(canonicalJson(artifact)).toBe(canonicalJson(testCase.expected.artifact))
        expect(artifact.workflowHash).toBe(platformWorkflowReleaseHash(result.artifact))
        const serialized = JSON.stringify(result.artifact)
        for (const forbidden of testCase.forbiddenStrings) {
          expect(serialized).not.toContain(forbidden)
        }
      } finally {
        rmSync(root, { recursive: true, force: true })
      }
    })
  }

  it('hash ignores environment-specific eval fields', async () => {
    const root = mkdtempSync(join(tmpdir(), 'gavio-workflow-'))
    try {
      writeFileSync(
        join(root, 'suite.json'),
        JSON.stringify({
          id: 'smoke',
          templates: [
            {
              id: 'hello',
              version: '1.0.0',
              messages: [{ role: 'user', content: 'Hi {{ name }}' }],
              requiredVariables: ['name'],
            },
          ],
          cases: [
            {
              id: 'greets',
              templateId: 'hello',
              variables: { name: 'Sam' },
              mockOutput: 'Hello Sam',
              assertions: [{ type: 'contains', value: 'Sam' }],
            },
          ],
        }),
      )
      writeFileSync(
        join(root, 'workflow.json'),
        JSON.stringify({ workflowId: 'smoke', generatedAt: '2026-07-12T12:00:00Z', evals: ['suite.json'] }),
      )

      const result = await runPlatformWorkflowReleaseFile(join(root, 'workflow.json'))
      const moved = JSON.parse(JSON.stringify(result.artifact)) as Record<string, unknown>
      const evalEntry = (moved.evals as Array<Record<string, unknown>>)[0]!
      evalEntry.source = '/somewhere/else/suite.json'
      evalEntry.runner = { name: 'gavio-eval-runner', version: '0', python: '3.12' }

      expect(platformWorkflowReleaseHash(moved)).toBe(result.artifact.workflowHash)
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })

  it('eval runner fails the gate below fail-under without storing raw output', async () => {
    const root = mkdtempSync(join(tmpdir(), 'gavio-eval-'))
    try {
      writeFileSync(
        join(root, 'suite.json'),
        JSON.stringify({
          id: 'gate',
          templates: [
            { id: 'hello', version: '1.0.0', messages: [{ role: 'user', content: 'Hi' }] },
          ],
          cases: [
            {
              id: 'bad',
              templateId: 'hello',
              mockOutput: 'secret card number',
              assertions: [{ type: 'not_contains', value: 'card number' }],
            },
          ],
        }),
      )

      const result = await runEvalFile(join(root, 'suite.json'), { failUnder: 1 })

      expect(result.passed).toBe(false)
      expect(result.gate.reasons).toContain('1 eval case(s) failed')
      expect(JSON.stringify(result.toJSON())).not.toContain('secret card number')
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})
