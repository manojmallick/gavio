import { describe, expect, it } from 'vitest'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  compatibilityMatrix,
  getIntegration,
  integrationAdapterPayload,
  integrationMetadata,
  listIntegrations,
} from '../../src/integrations.js'

const repoRoot = fileURLToPath(new URL('../../../..', import.meta.url))

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

const ecosystemTrust = JSON.parse(
  readFileSync(
    fileURLToPath(
      new URL('../../../../test-vectors/integrations/ecosystem-trust.json', import.meta.url),
    ),
    'utf8',
  ),
) as {
  since: string
  privacyBoundary: { forbiddenStrings: string[] }
  productionApps: Array<{
    id: string
    path: string
    readmePath: string
    covers: string[]
  }>
  cases: Array<{
    id: string
    expectedCategory: string
    adapterPayload: boolean
    requiredMetadata: string[]
    requiredSurfaces: string[]
    requiredExporters: string[]
    sampleApps: string[]
  }>
}

const trustMatrix = JSON.parse(
  readFileSync(
    fileURLToPath(new URL('../../../../docs/integrations/compatibility-matrix.json', import.meta.url)),
    'utf8',
  ),
) as {
  schemaVersion: string
  since: string
  summary: { integrations: number; productionApps: number }
  rows: Array<{
    id: string
    category: string
    privacyBoundary: string
    evidence: {
      catalog: string
      docs: string
      example: string
      metadataLabels: string[]
      adapterPayload: string
      productionApps: Array<{ id: string; path: string }>
    }
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

  it('keeps the generated ecosystem trust matrix in sync', () => {
    execFileSync('node', ['scripts/gen-ecosystem-trust-matrix.mjs', '--check'], {
      cwd: repoRoot,
      stdio: 'pipe',
    })

    const adapterIds = new Set(adapters.adapters.map((adapter) => adapter.id))
    const apps = new Map(ecosystemTrust.productionApps.map((app) => [app.id, app]))
    const rows = new Map(trustMatrix.rows.map((row) => [row.id, row]))

    expect(trustMatrix.schemaVersion).toBe('gavio.ecosystem-trust-matrix.v1')
    expect(trustMatrix.since).toBe('2.7.0')
    expect(trustMatrix.summary.integrations).toBe(ecosystemTrust.cases.length)
    expect(trustMatrix.summary.productionApps).toBe(ecosystemTrust.productionApps.length)

    for (const item of ecosystemTrust.cases) {
      const recipe = getIntegration(item.id)
      const row = rows.get(item.id)
      expect(row).toBeDefined()
      expect(recipe.category).toBe(item.expectedCategory)
      expect(row!.category).toBe(item.expectedCategory)
      expect(row!.privacyBoundary).toBe('metadata_only')
      expect(row!.evidence.catalog).toBe('pass')
      expect(row!.evidence.docs).toBe('pass')
      expect(row!.evidence.example).toBe('pass')
      expect(row!.evidence.metadataLabels).toEqual(item.requiredMetadata)
      expect(adapterIds.has(item.id)).toBe(item.adapterPayload)
      expect(row!.evidence.adapterPayload).toBe(item.adapterPayload ? 'pass' : 'not_applicable')

      for (const surface of item.requiredSurfaces) expect(recipe.gavioSurfaces).toContain(surface)
      for (const exporter of item.requiredExporters) {
        expect(recipe.recommendedExporters).toContain(exporter as 'jsonl' | 'otel')
      }

      expect(existsSync(resolve(repoRoot, recipe.docsPath))).toBe(true)
      expect(existsSync(resolve(repoRoot, recipe.examplePath))).toBe(true)
      expect(row!.evidence.productionApps.map((app) => app.id).sort()).toEqual(
        [...item.sampleApps].sort(),
      )

      for (const appId of item.sampleApps) {
        const app = apps.get(appId)
        expect(app).toBeDefined()
        expect(app!.covers).toContain(item.id)
        expect(existsSync(resolve(repoRoot, app!.path))).toBe(true)
        expect(existsSync(resolve(repoRoot, app!.readmePath))).toBe(true)
      }

      if (item.adapterPayload) {
        const payload = integrationAdapterPayload(item.id, adapters.source, {
          metadata: adapters.metadata,
        })
        const serialized = JSON.stringify(payload)
        for (const forbidden of ecosystemTrust.privacyBoundary.forbiddenStrings) {
          expect(serialized).not.toContain(forbidden)
        }
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
