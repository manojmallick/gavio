import { createHash } from 'node:crypto'
import { VERSION } from './version.js'
import type { AuditRecord } from './interceptors/audit/index.js'
import { verifyChain } from './interceptors/audit/index.js'

export const TRUST_SCHEMA_VERSION = '1.0'

const CONTENT_KEY_NAMES = new Set([
  'messages',
  'content',
  'diff',
  'rawmessages',
  'rawprompt',
  'rawresponse',
  'prompttext',
  'responsetext',
  'inputtext',
  'outputtext',
  'rawinput',
  'rawoutput',
])

export type TrustJsonValue =
  | string
  | number
  | boolean
  | null
  | TrustJsonValue[]
  | { [key: string]: TrustJsonValue }

export interface ProductionTrustBundle extends Record<string, unknown> {
  schemaVersion: string
  bundleId: string
  generatedAt: string
  sdk: Record<string, TrustJsonValue>
  release: Record<string, TrustJsonValue>
  runtime: Record<string, TrustJsonValue>
  privacy: Record<string, TrustJsonValue>
  evidence: {
    auditChain: {
      recordCount: number
      verified: boolean
      headHash: string
      tailHash: string
    }
    runtimeEvents: {
      eventCount: number
      contentFree: boolean
      eventTypes: string[]
    }
    controls: Array<Record<string, TrustJsonValue>>
  }
  documents: Array<Record<string, TrustJsonValue>>
  bundleHash: string
}

export interface ProductionTrustBundleOptions {
  bundleId: string
  generatedAt: string
  release: Record<string, TrustJsonValue>
  runtime: Record<string, TrustJsonValue>
  sdk?: Record<string, TrustJsonValue>
  auditRecords?: AuditRecord[]
  auditChainVerified?: boolean
  runtimeEvents?: Array<Record<string, TrustJsonValue>>
  controls?: Array<Record<string, TrustJsonValue>>
  documents?: Array<Record<string, TrustJsonValue>>
  privacy?: Record<string, TrustJsonValue>
}

export interface TrustBundleVerification {
  valid: boolean
  errors: string[]
  computedHash: string
}

export function buildProductionTrustBundle(
  options: ProductionTrustBundleOptions,
): ProductionTrustBundle {
  const records = options.auditRecords ?? []
  const events = options.runtimeEvents ?? []
  const auditChainVerified = options.auditChainVerified ?? verifyChain(records)
  const eventTypes = Array.from(
    new Set(
      events
        .map((event) => event.type)
        .filter((type): type is string => typeof type === 'string'),
    ),
  ).sort()

  const bundle: Omit<ProductionTrustBundle, 'bundleHash'> & { bundleHash?: string } = {
    schemaVersion: TRUST_SCHEMA_VERSION,
    bundleId: options.bundleId,
    generatedAt: options.generatedAt,
    sdk: options.sdk ?? { name: 'gavio-js', version: VERSION },
    release: options.release,
    runtime: options.runtime,
    privacy:
      options.privacy ??
      {
        contentMode: 'metadata_only',
        containsRawContent: false,
        redactedFields: ['messages', 'content', 'diff'],
      },
    evidence: {
      auditChain: auditChainSummary(records, auditChainVerified),
      runtimeEvents: {
        eventCount: events.length,
        contentFree: !containsContentKeys(events),
        eventTypes,
      },
      controls: options.controls ?? [],
    },
    documents: options.documents ?? [],
  }
  bundle.bundleHash = trustBundleHash(bundle)
  return bundle as ProductionTrustBundle
}

export function verifyProductionTrustBundle(
  bundle: Record<string, unknown>,
): TrustBundleVerification {
  const computedHash = trustBundleHash(bundle)
  const errors: string[] = []

  if (bundle.schemaVersion !== TRUST_SCHEMA_VERSION) {
    errors.push('schemaVersion must be 1.0')
  }
  if (bundle.bundleHash !== computedHash) {
    errors.push('bundleHash does not match bundle content')
  }
  if (containsContentKeys(bundle)) {
    errors.push('bundle contains content-bearing keys')
  }

  const privacy = asRecord(bundle.privacy)
  if (privacy.contentMode !== 'metadata_only') {
    errors.push('privacy.contentMode must be metadata_only')
  }
  if (privacy.containsRawContent !== false) {
    errors.push('privacy.containsRawContent must be false')
  }

  const evidence = asRecord(bundle.evidence)
  const auditChain = asRecord(evidence.auditChain)
  if (auditChain.verified !== true) {
    errors.push('evidence.auditChain.verified must be true')
  }
  const runtimeEvents = asRecord(evidence.runtimeEvents)
  if (runtimeEvents.contentFree !== true) {
    errors.push('evidence.runtimeEvents.contentFree must be true')
  }

  return { valid: errors.length === 0, errors, computedHash }
}

export function trustBundleHash(bundle: Record<string, unknown>): string {
  const canonical = stableStringify(withoutBundleHash(bundle))
  return `sha256:${createHash('sha256').update(canonical, 'utf8').digest('hex')}`
}

function auditChainSummary(
  records: AuditRecord[],
  verified: boolean,
): ProductionTrustBundle['evidence']['auditChain'] {
  const hashes = records.map((record) => record.contentHash())
  return {
    recordCount: records.length,
    verified,
    headHash: hashes[0] ? prefixedHash(hashes[0]) : '',
    tailHash: hashes.length ? prefixedHash(hashes[hashes.length - 1] as string) : '',
  }
}

function prefixedHash(value: string): string {
  return value.startsWith('sha256:') || value.length === 0 ? value : `sha256:${value}`
}

function withoutBundleHash(bundle: Record<string, unknown>): Record<string, unknown> {
  const { bundleHash: _bundleHash, ...rest } = bundle
  return rest
}

function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null'
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  const obj = value as Record<string, unknown>
  const parts = Object.keys(obj)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${stableStringify(obj[key])}`)
  return `{${parts.join(',')}}`
}

function containsContentKeys(value: unknown): boolean {
  if (Array.isArray(value)) return value.some((item) => containsContentKeys(item))
  if (value === null || typeof value !== 'object') return false

  for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
    const normalized = key.replace(/[_-]/g, '').toLowerCase()
    if (CONTENT_KEY_NAMES.has(normalized)) return true
    if (containsContentKeys(nested)) return true
  }
  return false
}

function asRecord(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}
