import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  compatibilityMatrix,
  getIntegration,
  integrationMetadata,
  listIntegrations,
} from '../../src/integrations.js'

const catalog = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../test-vectors/integrations/catalog.json', import.meta.url)),
    'utf8',
  ),
) as { recipes: unknown[] }

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
})
