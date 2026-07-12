import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  corePolicyPack,
  customPolicyPack,
  fintechPolicyPack,
  fintechScanners,
  piiGuard,
  policyPackScanners,
} from '../../src/interceptors/pii/index.js'
import { GavioTestKit } from '../../src/testing/index.js'

interface BuiltinPackVector {
  id: string
  name: string
  version: string
  domain: string
  defaultAction: string
  redactionStrategy: string
  auditLabels: string[]
  detectorEntityTypes: string[]
}

interface CustomRuleVector {
  id: string
  name: string
  version: string
  domain: string
  defaultAction: 'flag'
  redactionStrategy: 'hash'
  auditLabels: string[]
  rules: Array<{
    name: string
    entityType: string
    pattern: string
    confidence: number
    replacementPrefix: string
    action: 'flag'
    redactionStrategy: 'hash'
    label: string
  }>
  cases: Array<{ id: string; text: string; expectedTypes: string[] }>
}

const url = new URL('../../../../test-vectors/policy-packs/manifest.json', import.meta.url)
const vectors = JSON.parse(readFileSync(fileURLToPath(url), 'utf8')) as {
  builtinPacks: BuiltinPackVector[]
  customRulePack: CustomRuleVector
}

function detectorEntityTypes(manifest: ReturnType<ReturnType<typeof corePolicyPack>['manifest']>) {
  return manifest.detectors.map((detector) => detector.entityType)
}

async function detect(text: string, scanners = policyPackScanners(customPolicyPackFromVector())) {
  const kit = new GavioTestKit({
    interceptors: [piiGuard({ scanners, logEntityTypes: false })],
  })
  const result = await kit.run({ messages: [{ role: 'user', content: text }] })
  return [...new Set(result.ctx.piiEntityTypes)].sort()
}

function customPolicyPackFromVector() {
  const vector = vectors.customRulePack
  return customPolicyPack({
    id: vector.id,
    name: vector.name,
    version: vector.version,
    domain: vector.domain,
    rules: vector.rules,
    defaultAction: vector.defaultAction,
    redactionStrategy: vector.redactionStrategy,
    auditLabels: vector.auditLabels,
  })
}

describe('Policy Pack manifests', () => {
  it('exposes built-in core and FinTech manifests from shared vectors', () => {
    const packs = new Map([
      ['gavio.core-pii', corePolicyPack()],
      ['gavio.fintech', fintechPolicyPack()],
    ])
    for (const expected of vectors.builtinPacks) {
      const manifest = packs.get(expected.id)!.manifest()
      expect(manifest.id).toBe(expected.id)
      expect(manifest.name).toBe(expected.name)
      expect(manifest.version).toBe(expected.version)
      expect(manifest.domain).toBe(expected.domain)
      expect(manifest.defaultAction).toBe(expected.defaultAction)
      expect(manifest.redactionStrategy).toBe(expected.redactionStrategy)
      expect(manifest.auditLabels).toEqual(expected.auditLabels)
      expect(detectorEntityTypes(manifest)).toEqual(expected.detectorEntityTypes)
    }
  })

  it('keeps fintechScanners backed by the FinTech policy pack', () => {
    expect(fintechScanners().map((scanner) => scanner.entityType)).toEqual(
      fintechPolicyPack().detectors.map((detector) => detector.entityType),
    )
  })

  for (const c of vectors.customRulePack.cases) {
    it(`detects custom regex rule case: ${c.id}`, async () => {
      const pack = customPolicyPackFromVector()
      const manifest = pack.manifest()
      expect(manifest.id).toBe(vectors.customRulePack.id)
      expect(manifest.defaultAction).toBe(vectors.customRulePack.defaultAction)
      expect(manifest.redactionStrategy).toBe(vectors.customRulePack.redactionStrategy)
      expect(manifest.auditLabels).toEqual(vectors.customRulePack.auditLabels)
      expect(manifest.detectors[0]!.pattern).toBe(vectors.customRulePack.rules[0]!.pattern)
      expect(await detect(c.text, policyPackScanners(pack))).toEqual(c.expectedTypes)
    })
  }
})
