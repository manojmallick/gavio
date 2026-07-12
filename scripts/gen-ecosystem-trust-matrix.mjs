#!/usr/bin/env node

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const catalogPath = 'test-vectors/integrations/catalog.json'
const adaptersPath = 'test-vectors/integrations/adapters.json'
const conformancePath = 'test-vectors/integrations/ecosystem-trust.json'
const outputPath = 'docs/integrations/compatibility-matrix.json'

function readJson(path) {
  return JSON.parse(readFileSync(resolve(repoRoot, path), 'utf8'))
}

function sortObject(value) {
  if (Array.isArray(value)) return value.map(sortObject)
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((key) => [key, sortObject(value[key])]),
  )
}

function formatJson(value) {
  return `${JSON.stringify(sortObject(value), null, 2)}\n`
}

function buildMatrix() {
  const catalog = readJson(catalogPath)
  const adapters = readJson(adaptersPath)
  const conformance = readJson(conformancePath)
  const recipes = new Map(catalog.recipes.map((recipe) => [recipe.id, recipe]))
  const adapterIds = new Set(adapters.adapters.map((adapter) => adapter.id))
  const apps = new Map(conformance.productionApps.map((app) => [app.id, app]))

  if (recipes.size !== conformance.cases.length) {
    throw new Error(
      `conformance cases (${conformance.cases.length}) must match catalog recipes (${recipes.size})`,
    )
  }

  const rows = conformance.cases.map((item) => {
    const recipe = recipes.get(item.id)
    if (recipe === undefined) throw new Error(`unknown integration case: ${item.id}`)
    if (recipe.category !== item.expectedCategory) {
      throw new Error(`${item.id} category mismatch: ${recipe.category} != ${item.expectedCategory}`)
    }
    for (const key of item.requiredMetadata) {
      if (!(key in recipe.metadata)) throw new Error(`${item.id} missing metadata key: ${key}`)
    }
    for (const surface of item.requiredSurfaces) {
      if (!recipe.gavioSurfaces.includes(surface)) {
        throw new Error(`${item.id} missing required surface: ${surface}`)
      }
    }
    for (const exporter of item.requiredExporters) {
      if (!recipe.recommendedExporters.includes(exporter)) {
        throw new Error(`${item.id} missing required exporter: ${exporter}`)
      }
    }
    if (item.adapterPayload !== adapterIds.has(item.id)) {
      throw new Error(`${item.id} adapter payload coverage disagrees with adapter vector`)
    }

    const coveredBy = item.sampleApps.map((appId) => {
      const app = apps.get(appId)
      if (app === undefined) throw new Error(`${item.id} references unknown app: ${appId}`)
      if (!app.covers.includes(item.id)) throw new Error(`${appId} does not cover ${item.id}`)
      if (!existsSync(resolve(repoRoot, app.path))) throw new Error(`missing app: ${app.path}`)
      if (!existsSync(resolve(repoRoot, app.readmePath))) {
        throw new Error(`missing app readme: ${app.readmePath}`)
      }
      return {
        id: app.id,
        name: app.name,
        path: app.path,
        smokeCommand: app.smokeCommand,
      }
    })

    return {
      id: recipe.id,
      name: recipe.name,
      category: recipe.category,
      trustLevel: 'conformance-tested',
      privacyBoundary: conformance.privacyBoundary.contentMode,
      externalOwns: recipe.externalOwns,
      gavioOwns: recipe.gavioOwns,
      gavioSurfaces: recipe.gavioSurfaces,
      recommendedExporters: recipe.recommendedExporters,
      docsPath: recipe.docsPath,
      examplePath: recipe.examplePath,
      evidence: {
        catalog: 'pass',
        docs: existsSync(resolve(repoRoot, recipe.docsPath)) ? 'pass' : 'missing',
        example: existsSync(resolve(repoRoot, recipe.examplePath)) ? 'pass' : 'missing',
        metadataLabels: item.requiredMetadata,
        adapterPayload: item.adapterPayload ? 'pass' : 'not_applicable',
        productionApps: coveredBy,
      },
    }
  })

  const appCoverage = Object.fromEntries(
    conformance.productionApps.map((app) => [
      app.id,
      {
        name: app.name,
        path: app.path,
        covers: app.covers,
        expected: app.expected,
      },
    ]),
  )

  return {
    schemaVersion: 'gavio.ecosystem-trust-matrix.v1',
    since: conformance.since,
    generatedFrom: [catalogPath, adaptersPath, conformancePath],
    summary: {
      integrations: rows.length,
      adapterPayloads: rows.filter((row) => row.evidence.adapterPayload === 'pass').length,
      productionApps: conformance.productionApps.length,
      privacyBoundary: conformance.privacyBoundary.contentMode,
    },
    productionApps: appCoverage,
    rows,
  }
}

const output = formatJson(buildMatrix())
const outputFile = resolve(repoRoot, outputPath)

if (process.argv.includes('--check')) {
  const current = readFileSync(outputFile, 'utf8')
  if (current !== output) {
    console.error(`${outputPath} is out of date. Run: node scripts/gen-ecosystem-trust-matrix.mjs`)
    process.exit(1)
  }
} else {
  writeFileSync(outputFile, output)
}
