/** Policy Pack framework (F-PACK-01/02/05). */

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

export interface PolicyPackDetector {
  name: string
  entityType: string
  type: 'scanner' | 'regex'
  action: PolicyAction
  label?: string
  confidence: number
  redactionStrategy: RedactionStrategy
  pattern?: string
}

export interface PolicyPackManifest {
  id: string
  name: string
  version: string
  domain: string
  description: string
  defaultAction: PolicyAction
  redactionStrategy: RedactionStrategy
  auditLabels: string[]
  detectors: PolicyPackDetector[]
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
  manifest(): PolicyPackManifest
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
  ) {}

  manifest(): PolicyPackManifest {
    return {
      id: this.id,
      name: this.name,
      version: this.version,
      domain: this.domain,
      description: this.description,
      defaultAction: this.defaultAction,
      redactionStrategy: this.redactionStrategy,
      auditLabels: [...this.auditLabels],
      detectors: this.detectors.map((detector) => ({ ...detector })),
    }
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
  return {
    entityType: rule.entityType,
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      const re = new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : `${pattern.flags}g`)
      for (const match of text.matchAll(re)) {
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
    confidence: rule.confidence ?? 1.0,
    redactionStrategy: rule.redactionStrategy ?? redactionStrategy,
    pattern: patternSource(rule.pattern),
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
