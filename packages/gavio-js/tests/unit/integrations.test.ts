import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  compatibilityMatrix,
  getIntegration,
  integrationAdapterPayload,
  integrationMetadata,
  listIntegrations,
} from '../../src/integrations.js'

const catalog = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../test-vectors/integrations/catalog.json', import.meta.url)),
    'utf8',
  ),
) as { recipes: unknown[] }

const adapters = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../test-vectors/integrations/adapters.json', import.meta.url)),
    'utf8',
  ),
) as {
  source: Record<string, unknown>
  metadata: Record<string, unknown>
  forbiddenStrings: string[]
  adapters: Array<{
    id: string
    kind: string
    expects: Array<{ path: Array<string | number>; value?: unknown; absent?: boolean }>
  }>
}

describe('integration catalog', () => {
  it('matches the shared vector', () => {
    expect(listIntegrations()).toEqual(catalog.recipes)
  })

  it('adds request metadata labels', () => {
    expect(
      integrationMetadata('litellm', {
        tenant: 'acme',
        feature: 'support-chat',
        environment: 'prod',
      }),
    ).toEqual({
      gateway: 'litellm',
      integration: 'litellm',
      integration_kind: 'gateway',
      tenant: 'acme',
      feature: 'support-chat',
      environment: 'prod',
    })
  })

  it('filters and raises cleanly', () => {
    expect(listIntegrations({ category: 'observability' }).map((recipe) => recipe.id)).toEqual([
      'langfuse',
      'openlit',
    ])
    expect(getIntegration('openlit').recommendedExporters).toEqual(['otel'])
    expect(() => getIntegration('missing')).toThrow('unknown Gavio integration')
  })

  it('returns a docs-oriented compatibility matrix', () => {
    const matrix = compatibilityMatrix()

    expect(matrix).toHaveLength(catalog.recipes.length)
    const first = matrix[0]
    expect(first).toBeDefined()
    expect(first).not.toHaveProperty('metadata')
    expect(first!.docsPath).toBe('docs/integrations/litellm.md')
  })

  it('builds adapter payloads from the shared vector', () => {
    for (const adapter of adapters.adapters) {
      const payload = integrationAdapterPayload(adapter.id, adapters.source, {
        metadata: adapters.metadata,
      })

      expect(payload.schemaVersion).toBe('gavio.integration-adapter.v1')
      expect(payload.adapter).toBe(adapter.id)
      expect(payload.target).toBe(adapter.id)
      expect(payload.kind).toBe(adapter.kind)
      for (const expectation of adapter.expects) {
        if (expectation.absent === true) expect(missing(payload, expectation.path)).toBe(true)
        else expect(at(payload, expectation.path)).toEqual(expectation.value)
      }
      const serialized = JSON.stringify(payload)
      for (const forbidden of adapters.forbiddenStrings) {
        expect(serialized).not.toContain(forbidden)
      }
    }
  })
})

function at(value: unknown, path: Array<string | number>): unknown {
  let current = value
  for (const part of path) {
    if (typeof part === 'number') {
      expect(Array.isArray(current)).toBe(true)
      current = (current as unknown[])[part]
    } else {
      expect(current).toBeTypeOf('object')
      expect(current).not.toBeNull()
      current = (current as Record<string, unknown>)[part]
    }
  }
  return current
}

function missing(value: unknown, path: Array<string | number>): boolean {
  let current = value
  for (const part of path) {
    if (typeof part === 'number') {
      if (!Array.isArray(current) || part >= current.length) return true
      current = current[part]
    } else {
      if (current === null || typeof current !== 'object' || !(part in current)) return true
      current = (current as Record<string, unknown>)[part]
    }
  }
  return false
}
