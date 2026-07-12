/** Policy Pack framework (F-PACK-01/02/05). */

import { createHash } from 'node:crypto'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import type { ScanContext } from './context.js'
import { makeMatch } from './match.js'
import type { PiiMatch } from './match.js'
import type { PiiScanner } from './scanner.js'
import { bsnScanner } from './scanners/bsn.js'
import { creditCardScanner } from './scanners/credit-card.js'
import { emailScanner } from './scanners/email.js'
import { ibanScanner } from './scanners/iban.js'
import { ipAddressScanner } from './scanners/ip-address.js'
import { phoneScanner } from './scanners/phone.js'
import { routingNumberScanner } from './scanners/routing-number.js'
import { secretScanner } from './scanners/secret.js'
import { ssnScanner } from './scanners/ssn.js'
import { swiftBicScanner } from './scanners/swift-bic.js'

export type PolicyAction =
  | 'allow'
  | 'flag'
  | 'redact'
  | 'mask'
  | 'hash'
  | 'block'
  | 'route'
  | 'require-approval'

export type RedactionStrategy = 'tokenize' | 'mask' | 'hash' | 'redact'
export type PolicySeverity = 'low' | 'medium' | 'high' | 'critical'

export interface PolicyPackSignature {
  algorithm: 'sha256'
  value?: string | null
  keyId?: string
  signedAt?: string
}

export interface PolicyPackDetector {
  name: string
  entityType: string
  type: 'scanner' | 'regex'
  action: PolicyAction
  label?: string
  severity?: PolicySeverity
  confidence: number
  redactionStrategy: RedactionStrategy
  pattern?: string
  replacementPrefix?: string
  suppressionPatterns?: string[]
}

export interface PolicyPackManifest {
  $schema?: string
  schemaVersion?: string
  id: string
  name: string
  version: string
  domain: string
  description: string
  compatibility?: Record<string, string>
  defaultAction: PolicyAction
  redactionStrategy: RedactionStrategy
  auditLabels: string[]
  detectors: PolicyPackDetector[]
  signature?: PolicyPackSignature
}

export interface PolicyPack {
  id: string
  name: string
  version: string
  domain: string
  description: string
  detectors: PolicyPackDetector[]
  scanners: PiiScanner[]
  defaultAction: PolicyAction
  redactionStrategy: RedactionStrategy
  auditLabels: string[]
  compatibility: Record<string, string>
  signature?: PolicyPackSignature
  manifest(): PolicyPackManifest
  verifySignature(): boolean
  withOverrides(overrides: PolicyPackOverrides): PolicyPack
}

export interface RegexPolicyRule {
  name: string
  entityType: string
  pattern: string | RegExp
  confidence?: number
  replacementPrefix?: string
  action?: PolicyAction
  redactionStrategy?: RedactionStrategy
  label?: string
  severity?: PolicySeverity
  suppressionPatterns?: string[]
}

export interface CustomPolicyPackOptions {
  id: string
  name: string
  rules: RegexPolicyRule[]
  version?: string
  domain?: string
  description?: string
  defaultAction?: PolicyAction
  redactionStrategy?: RedactionStrategy
  auditLabels?: string[]
}

export interface PolicyPackOverrides {
  defaultAction?: PolicyAction
  redactionStrategy?: RedactionStrategy
  auditLabels?: string[]
  detectors?: Record<string, Partial<PolicyPackDetector>>
}

class BasicPolicyPack implements PolicyPack {
  constructor(
    readonly id: string,
    readonly name: string,
    readonly version: string,
    readonly domain: string,
    readonly description: string,
    readonly detectors: PolicyPackDetector[],
    readonly scanners: PiiScanner[],
    readonly defaultAction: PolicyAction = 'redact',
    readonly redactionStrategy: RedactionStrategy = 'tokenize',
    readonly auditLabels: string[] = [],
    readonly compatibility: Record<string, string> = {},
    readonly signature?: PolicyPackSignature,
    private readonly schema?: string,
    private readonly schemaVersion?: string,
  ) {}

  manifest(): PolicyPackManifest {
    const out: PolicyPackManifest = {
      id: this.id,
      name: this.name,
      version: this.version,
      domain: this.domain,
      description: this.description,
      defaultAction: this.defaultAction,
      redactionStrategy: this.redactionStrategy,
      auditLabels: [...this.auditLabels],
      detectors: this.detectors.map(cleanDetector),
    }
    if (this.schema) out.$schema = this.schema
    if (this.schemaVersion) out.schemaVersion = this.schemaVersion
    if (Object.keys(this.compatibility).length > 0) out.compatibility = { ...this.compatibility }
    if (this.signature) out.signature = { ...this.signature }
    return out
  }

  verifySignature(): boolean {
    if (!this.signature || this.signature.algorithm !== 'sha256' || !this.signature.value) return false
    return canonicalManifestDigest(this.manifest()) === this.signature.value
  }

  withOverrides(overrides: PolicyPackOverrides): PolicyPack {
    const manifest = this.manifest()
    if (overrides.defaultAction) manifest.defaultAction = overrides.defaultAction
    if (overrides.redactionStrategy) manifest.redactionStrategy = overrides.redactionStrategy
    if (overrides.auditLabels) manifest.auditLabels = [...overrides.auditLabels]
    for (const detector of manifest.detectors) {
      const override = overrides.detectors?.[detector.name]
      if (override) Object.assign(detector, override)
    }
    delete manifest.signature
    return policyPackFromManifest(manifest)
  }
}

function detector(
  name: string,
  entityType: string,
  label: string,
  action: PolicyAction = 'redact',
  redactionStrategy: RedactionStrategy = 'tokenize',
): PolicyPackDetector {
  return {
    name,
    entityType,
    type: 'scanner',
    action,
    label,
    confidence: 1.0,
    redactionStrategy,
  }
}

function patternSource(pattern: string | RegExp): string {
  return typeof pattern === 'string' ? pattern : pattern.source
}

export function regexRuleScanner(rule: RegexPolicyRule): PiiScanner {
  const pattern = typeof rule.pattern === 'string' ? new RegExp(rule.pattern, 'g') : rule.pattern
  const suppressions = (rule.suppressionPatterns ?? []).map((source) => new RegExp(source))
  return {
    entityType: rule.entityType,
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      const re = new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : `${pattern.flags}g`)
      for (const match of text.matchAll(re)) {
        if (suppressions.some((suppression) => suppression.test(match[0]))) continue
        const idx = ctx.nextIndex(rule.entityType)
        const prefix = rule.replacementPrefix ?? rule.entityType
        const start = match.index ?? 0
        out.push(
          makeMatch({
            entityType: rule.entityType,
            start,
            end: start + match[0].length,
            value: match[0],
            confidence: rule.confidence ?? 1.0,
            replacement: `[${prefix}_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}

export function corePolicyPack(): PolicyPack {
  return new BasicPolicyPack(
    'gavio.core-pii',
    'Core PII',
    '0.12.0',
    'core',
    'Built-in deterministic PII scanners.',
    [
      detector('secret', 'SECRET', 'PII'),
      detector('email', 'EMAIL', 'PII'),
      detector('iban', 'IBAN', 'PII'),
      detector('bsn', 'BSN', 'PII'),
      detector('credit_card', 'CREDIT_CARD', 'PII'),
      detector('ssn', 'SSN', 'PII'),
      detector('phone', 'PHONE', 'PII'),
      detector('ip_address', 'IP_ADDRESS', 'PII'),
    ],
    [
      secretScanner(),
      emailScanner(),
      ibanScanner(),
      bsnScanner(),
      creditCardScanner(),
      ssnScanner(),
      phoneScanner(),
      ipAddressScanner(),
    ],
    'redact',
    'tokenize',
    ['PII'],
  )
}

export function fintechPolicyPack(): PolicyPack {
  return new BasicPolicyPack(
    'gavio.fintech',
    'FinTech',
    '0.12.0',
    'fintech',
    'Financial identifiers beyond the core PII pack.',
    [
      detector('swift_bic', 'SWIFT_BIC', 'FINANCIAL_IDENTIFIER'),
      detector('routing_number', 'ROUTING_NUMBER', 'FINANCIAL_IDENTIFIER'),
    ],
    [swiftBicScanner(), routingNumberScanner()],
    'redact',
    'tokenize',
    ['FINANCIAL_IDENTIFIER'],
  )
}

export function customPolicyPack(options: CustomPolicyPackOptions): PolicyPack {
  const defaultAction = options.defaultAction ?? 'redact'
  const redactionStrategy = options.redactionStrategy ?? 'tokenize'
  const detectors: PolicyPackDetector[] = options.rules.map((rule) => ({
    name: rule.name,
    entityType: rule.entityType,
    type: 'regex',
    action: rule.action ?? defaultAction,
    label: rule.label,
    severity: rule.severity,
    confidence: rule.confidence ?? 1.0,
    redactionStrategy: rule.redactionStrategy ?? redactionStrategy,
    pattern: patternSource(rule.pattern),
    replacementPrefix: rule.replacementPrefix,
    suppressionPatterns: rule.suppressionPatterns,
  }))
  return new BasicPolicyPack(
    options.id,
    options.name,
    options.version ?? '1.0.0',
    options.domain ?? 'custom',
    options.description ?? 'Custom organization policy pack.',
    detectors,
    options.rules.map((rule) => regexRuleScanner(rule)),
    defaultAction,
    redactionStrategy,
    options.auditLabels ?? [],
  )
}

export function policyPackScanners(...packs: PolicyPack[]): PiiScanner[] {
  return packs.flatMap((pack) => pack.scanners)
}

export function listPolicyPacks(): string[] {
  const root = catalogRoot()
  const found: string[] = []
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry)
      if (statSync(full).isDirectory()) {
        const manifest = join(full, 'manifest.json')
        if (existsSync(manifest)) found.push(relative(root, full).split('\\').join('/'))
        walk(full)
      }
    }
  }
  walk(root)
  return found.sort()
}

export function loadPolicyPack(name: string): PolicyPack {
  const path = join(catalogRoot(), name, 'manifest.json')
  if (!existsSync(path)) throw new Error(`unknown policy pack: ${name}`)
  return loadPolicyPackPath(path)
}

export function loadPolicyPackPath(path: string): PolicyPack {
  const manifestPath = existsSync(join(path, 'manifest.json')) ? join(path, 'manifest.json') : path
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as PolicyPackManifest
  return policyPackFromManifest(manifest)
}

export function policyPackFromManifest(manifest: PolicyPackManifest): PolicyPack {
  const defaultAction = manifest.defaultAction ?? 'redact'
  const redactionStrategy = manifest.redactionStrategy ?? 'tokenize'
  const detectors = manifest.detectors.map((item) => detectorFromManifest(item, defaultAction, redactionStrategy))
  const scanners = detectors.flatMap(scannersFromDetector)
  return new BasicPolicyPack(
    manifest.id,
    manifest.name,
    manifest.version,
    manifest.domain,
    manifest.description,
    detectors,
    scanners,
    defaultAction,
    redactionStrategy,
    manifest.auditLabels ?? [],
    manifest.compatibility ?? {},
    manifest.signature,
    manifest.$schema,
    manifest.schemaVersion,
  )
}

function detectorFromManifest(
  item: PolicyPackDetector,
  defaultAction: PolicyAction,
  defaultStrategy: RedactionStrategy,
): PolicyPackDetector {
  return cleanDetector({
    name: item.name,
    entityType: item.entityType,
    type: item.type ?? 'scanner',
    action: item.action ?? defaultAction,
    label: item.label,
    severity: item.severity,
    confidence: item.confidence ?? 1.0,
    redactionStrategy: item.redactionStrategy ?? defaultStrategy,
    pattern: item.pattern,
    replacementPrefix: item.replacementPrefix,
    suppressionPatterns: item.suppressionPatterns,
  })
}

function scannersFromDetector(detector: PolicyPackDetector): PiiScanner[] {
  if (detector.type === 'regex') {
    if (!detector.pattern) throw new Error(`regex policy detector ${detector.name} is missing pattern`)
    return [
      regexRuleScanner({
        name: detector.name,
        entityType: detector.entityType,
        pattern: detector.pattern,
        confidence: detector.confidence,
        replacementPrefix: detector.replacementPrefix,
        action: detector.action,
        redactionStrategy: detector.redactionStrategy,
        label: detector.label,
        severity: detector.severity,
        suppressionPatterns: detector.suppressionPatterns,
      }),
    ]
  }
  const scanner = BUILTIN_SCANNERS[detector.name] ?? BUILTIN_SCANNERS[detector.entityType]
  if (!scanner) throw new Error(`unknown policy-pack scanner detector: ${detector.name}`)
  return [scanner()]
}

function cleanDetector(detector: PolicyPackDetector): PolicyPackDetector {
  return Object.fromEntries(Object.entries(detector).filter(([, value]) => value !== undefined)) as PolicyPackDetector
}

function canonicalManifestDigest(manifest: PolicyPackManifest): string {
  const payload = JSON.parse(JSON.stringify(manifest)) as PolicyPackManifest
  if (payload.signature) payload.signature.value = null
  return createHash('sha256').update(stableStringify(payload)).digest('hex')
}

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  if (value && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, item]) => item !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${stableStringify(item)}`).join(',')}}`
  }
  return JSON.stringify(value)
}

function catalogRoot(): string {
  for (const base of parentDirs(process.cwd())) {
    const candidate = join(base, 'policy-packs')
    if (existsSync(candidate)) return candidate
  }
  throw new Error('could not locate policy-packs catalog')
}

function parentDirs(start: string): string[] {
  const out: string[] = []
  let current = resolve(start)
  while (true) {
    out.push(current)
    const next = dirname(current)
    if (next === current) return out
    current = next
  }
}

const BUILTIN_SCANNERS: Record<string, () => PiiScanner> = {
  secret: secretScanner,
  SECRET: secretScanner,
  email: emailScanner,
  EMAIL: emailScanner,
  iban: ibanScanner,
  IBAN: ibanScanner,
  bsn: bsnScanner,
  BSN: bsnScanner,
  credit_card: creditCardScanner,
  CREDIT_CARD: creditCardScanner,
  ssn: ssnScanner,
  SSN: ssnScanner,
  phone: phoneScanner,
  PHONE: phoneScanner,
  ip_address: ipAddressScanner,
  IP_ADDRESS: ipAddressScanner,
  swift_bic: swiftBicScanner,
  SWIFT_BIC: swiftBicScanner,
  routing_number: routingNumberScanner,
  ROUTING_NUMBER: routingNumberScanner,
}
