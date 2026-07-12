import { createHash } from 'node:crypto'
import { VERSION } from './version.js'

export const PLATFORM_RUNTIME_SCHEMA_VERSION = '2.0'

export const DEFAULT_PLATFORM_REQUIRED_SURFACES = [
  'runtime_events',
  'audit_hashes',
  'policy_packs',
  'cost_governance',
  'tool_runtime',
  'trust_evidence',
] as const

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

export type PlatformJsonValue =
  | string
  | number
  | boolean
  | null
  | PlatformJsonValue[]
  | { [key: string]: PlatformJsonValue }

export type PlatformRuntimeSurface =
  | 'runtime_events'
  | 'otel_spans'
  | 'audit_hashes'
  | 'cost_governance'
  | 'policy_packs'
  | 'tool_runtime'
  | 'prompt_evals'
  | 'control_plane'
  | 'trust_evidence'
  | 'integration_catalog'

export interface PlatformRuntimeGap {
  code: string
  severity: 'error'
  message: string
}

export interface PlatformRuntimeReadiness {
  ready: boolean
  score: number
  requiredSurfaces: string[]
  gaps: PlatformRuntimeGap[]
}

export interface PlatformRuntimeProfile extends Record<string, unknown> {
  schemaVersion: '2.0'
  profileId: string
  generatedAt: string
  sdk: Record<string, PlatformJsonValue>
  runtime: Record<string, PlatformJsonValue>
  surfaces: string[]
  exporters: string[]
  integrations: string[]
  controls: Array<Record<string, PlatformJsonValue>>
  evidence: Record<string, PlatformJsonValue>
  requirements: { requiredSurfaces: string[] }
  readiness: PlatformRuntimeReadiness
  profileHash: string
}

export interface PlatformRuntimeProfileOptions {
  profileId: string
  generatedAt: string
  runtime: Record<string, PlatformJsonValue>
  surfaces: string[]
  exporters?: string[]
  integrations?: string[]
  controls?: Array<Record<string, PlatformJsonValue>>
  evidence?: Record<string, PlatformJsonValue>
  sdk?: Record<string, PlatformJsonValue>
  requiredSurfaces?: string[]
}

export interface PlatformRuntimeVerification {
  valid: boolean
  errors: string[]
  computedHash: string
  readiness: PlatformRuntimeReadiness
}

export function buildPlatformRuntimeProfile(
  options: PlatformRuntimeProfileOptions,
): PlatformRuntimeProfile {
  const requirements = {
    requiredSurfaces: uniqueSorted(
      options.requiredSurfaces ?? [...DEFAULT_PLATFORM_REQUIRED_SURFACES],
    ),
  }
  const profile: Omit<PlatformRuntimeProfile, 'profileHash'> & { profileHash?: string } = {
    schemaVersion: PLATFORM_RUNTIME_SCHEMA_VERSION,
    profileId: options.profileId,
    generatedAt: options.generatedAt,
    sdk: options.sdk ?? { name: 'gavio-js', version: VERSION },
    runtime: { ...options.runtime },
    surfaces: uniqueSorted(options.surfaces),
    exporters: uniqueSorted(options.exporters ?? []),
    integrations: uniqueSorted(options.integrations ?? []),
    controls: (options.controls ?? []).map((control) => ({ ...control })),
    evidence: defaultEvidence(options.evidence),
    requirements,
    readiness: {
      ready: false,
      score: 0,
      requiredSurfaces: requirements.requiredSurfaces,
      gaps: [],
    },
  }
  profile.readiness = platformRuntimeReadiness(profile)
  profile.profileHash = platformProfileHash(profile)
  return profile as PlatformRuntimeProfile
}

export function verifyPlatformRuntimeProfile(
  profile: Record<string, unknown>,
): PlatformRuntimeVerification {
  const computedHash = platformProfileHash(profile)
  const readiness = platformRuntimeReadiness(profile)
  const errors: string[] = []

  if (profile.schemaVersion !== PLATFORM_RUNTIME_SCHEMA_VERSION) {
    errors.push('schemaVersion must be 2.0')
  }
  if (profile.profileHash !== computedHash) {
    errors.push('profileHash does not match profile content')
  }
  if (containsContentKeys(profile)) {
    errors.push('profile contains content-bearing keys')
  }
  if (JSON.stringify(profile.readiness) !== JSON.stringify(readiness)) {
    errors.push('readiness does not match profile content')
  }

  return { valid: errors.length === 0, errors, computedHash, readiness }
}

export function platformProfileHash(profile: Record<string, unknown>): string {
  const canonical = stableStringify(withoutProfileHash(profile))
  return `sha256:${createHash('sha256').update(canonical, 'utf8').digest('hex')}`
}

export function platformRuntimeReadiness(profile: Record<string, unknown>): PlatformRuntimeReadiness {
  const requirements = asRecord(profile.requirements)
  const requiredSurfaces = uniqueSorted(
    Array.isArray(requirements.requiredSurfaces)
      ? requirements.requiredSurfaces.map(String)
      : [...DEFAULT_PLATFORM_REQUIRED_SURFACES],
  )
  const surfaces = new Set(Array.isArray(profile.surfaces) ? profile.surfaces.map(String) : [])
  const runtime = asRecord(profile.runtime)
  const evidence = asRecord(profile.evidence)
  const runtimeEvents = asRecord(evidence.runtimeEvents)
  const auditChain = asRecord(evidence.auditChain)
  const controls = Array.isArray(profile.controls)
    ? profile.controls.filter((control): control is Record<string, unknown> => isRecord(control))
    : []

  const gaps: PlatformRuntimeGap[] = []
  for (const surface of requiredSurfaces) {
    if (!surfaces.has(surface)) {
      gaps.push(gap(`missing_surface:${surface}`, `required surface ${surface} is not enabled`))
    }
  }
  if (runtime.eventExportMode !== 'metadata_only') {
    gaps.push(gap('runtime.event_export_mode', 'runtime.eventExportMode must be metadata_only'))
  }
  if (runtimeEvents.contentFree !== true) {
    gaps.push(gap('runtime_events.content_free', 'runtime event evidence must be content-free'))
  }
  if (auditChain.verified !== true) {
    gaps.push(gap('audit_chain.verified', 'audit-chain evidence must be verified'))
  }
  for (const control of controls) {
    if (control.status === 'fail') {
      const controlId = String(control.id ?? 'unknown')
      gaps.push(gap(`control_failed:${controlId}`, `control ${controlId} failed`))
    }
  }

  const totalChecks = Math.max(1, requiredSurfaces.length + 3 + controls.length)
  const score = Math.max(0, Math.round((100 * (totalChecks - gaps.length)) / totalChecks))
  return { ready: gaps.length === 0, score, requiredSurfaces, gaps }
}

function defaultEvidence(value: Record<string, PlatformJsonValue> | undefined): Record<string, PlatformJsonValue> {
  const evidence: Record<string, PlatformJsonValue> = { ...(value ?? {}) }
  if (!isRecord(evidence.auditChain)) {
    evidence.auditChain = { recordCount: 0, verified: false }
  }
  if (!isRecord(evidence.runtimeEvents)) {
    evidence.runtimeEvents = { eventCount: 0, contentFree: false }
  }
  return evidence
}

function gap(code: string, message: string): PlatformRuntimeGap {
  return { code, severity: 'error', message }
}

function uniqueSorted(values: Array<string | number | boolean>): string[] {
  return Array.from(new Set(values.map(String))).sort()
}

function withoutProfileHash(profile: Record<string, unknown>): Record<string, unknown> {
  const { profileHash: _profileHash, ...rest } = profile
  return rest
}

function containsContentKeys(value: unknown): boolean {
  if (isRecord(value)) {
    for (const [key, nested] of Object.entries(value)) {
      const normalized = key.replace(/[_-]/g, '').toLowerCase()
      if (CONTENT_KEY_NAMES.has(normalized)) return true
      if (containsContentKeys(nested)) return true
    }
  }
  if (Array.isArray(value)) return value.some((item) => containsContentKeys(item))
  return false
}

function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map((item) => stableStringify(item)).join(',')}]`
  const entries = Object.entries(value as Record<string, unknown>).sort(([a], [b]) =>
    a.localeCompare(b),
  )
  return `{${entries
    .map(([key, nested]) => `${JSON.stringify(key)}:${stableStringify(nested)}`)
    .join(',')}}`
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function asRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {}
}
