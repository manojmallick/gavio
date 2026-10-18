/**
 * Platform workflow release artifacts (F-DX-13).
 *
 * Joins prompts, evals, policy packs, production trust evidence, and platform
 * runtime profiles into one metadata-only release record. Library port of
 * `gavio.workflow` (Python): same manifest, same artifact, same
 * `workflowHash`. The `gavio workflow release` CLI stays Python-only, and only
 * JSON documents are read here (YAML manifests are Python-only). The artifact
 * shape is `spec/PlatformWorkflowRelease.schema.json`; behaviour is pinned by
 * `test-vectors/workflow/release.json`.
 */

import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { dirname, isAbsolute, resolve } from 'node:path'

import { loadPolicyPack, loadPolicyPackPath } from './interceptors/pii/policy-pack.js'
import { verifyPlatformRuntimeProfile } from './platform-runtime.js'
import { buildPromptReleaseBundle } from './prompts/index.js'
import type { PromptManifest, PromptReleaseBundle } from './prompts/index.js'
import { loadEvalDocument, runEvalFile, wireGate } from './prompts/runner.js'
import type { EvalRunResult } from './prompts/runner.js'
import { verifyProductionTrustBundle } from './trust.js'

export const WORKFLOW_MANIFEST_SCHEMA_VERSION = 'gavio.platform-workflow.v1'
export const WORKFLOW_RELEASE_SCHEMA_VERSION = 'gavio.platform-workflow-release.v1'

/** Fields of an `evals[*]` entry that describe the machine that ran the suite. */
const ENVIRONMENT_EVAL_KEYS = new Set(['runner', 'source'])

const CONTENT_KEY_NAMES = new Set([
  'messages',
  'content',
  'diff',
  'raw',
  'rawmessages',
  'rawprompt',
  'rawresponse',
  'prompt',
  'prompttext',
  'completion',
  'response',
  'responsetext',
  'input',
  'inputtext',
  'output',
  'outputtext',
  'rawinput',
  'rawoutput',
  'renderedprompt',
  'text',
])

type Mapping = Record<string, unknown>

export interface PlatformWorkflowReleaseResult {
  artifact: Mapping
  passed: boolean
  reasons: string[]
}

export interface BuildPlatformWorkflowReleaseOptions {
  /** Directory relative paths in the manifest resolve against (default: cwd). */
  basePath?: string
}

/** Load a workflow manifest and build its release artifact. */
export async function runPlatformWorkflowReleaseFile(
  manifestPath: string,
): Promise<PlatformWorkflowReleaseResult> {
  const path = resolve(expandUser(manifestPath))
  const manifest = loadEvalDocument(path)
  return buildPlatformWorkflowRelease(manifest, { basePath: dirname(path) })
}

/** Build a deterministic metadata-only platform workflow release. */
export async function buildPlatformWorkflowRelease(
  manifest: Mapping,
  options: BuildPlatformWorkflowReleaseOptions = {},
): Promise<PlatformWorkflowReleaseResult> {
  const base = resolve(expandUser(options.basePath ?? '.'))
  const workflowId = pyStr(get(manifest, 'workflowId', get(manifest, 'workflow_id', 'workflow-release')))
  const generatedAt = pyStr(
    truthy(manifest.generatedAt) ?? truthy(manifest.generated_at) ?? utcNow(),
  )

  const promptSection = asMapping(get(manifest, 'prompts', get(manifest, 'prompt')))
  const promptManifestPath = optionalPath(base, promptSection.manifest)
  const promptManifest =
    promptManifestPath === null ? null : (loadEvalDocument(promptManifestPath) as unknown as PromptManifest)

  const evalResults = await runEvalSections(manifest, base, promptManifestPath)
  const promptBundles = buildPromptBundles(
    promptSection,
    promptManifest,
    evalResults.map((result) => result.report),
    generatedAt,
  )
  const policyEvidence = policyEvidenceFor(manifest, base)
  const trustEvidence = trustEvidenceFor(manifest, base)
  const profileEvidence = profileEvidenceFor(manifest, base)

  const reasons: string[] = []
  for (const result of evalResults) {
    if (!result.passed) {
      reasons.push(...result.gate.reasons.map((reason) => `eval:${reason}`))
      if (result.workflow !== null && !result.workflow.passed) {
        for (const gate of result.workflow.gates) {
          reasons.push(...gate.reasons.map((reason) => `prompt:${reason}`))
        }
      }
    }
  }
  for (const bundle of promptBundles) {
    if (bundle.passed !== true) reasons.push(`prompt release bundle ${String(bundle.bundleId)} did not pass`)
  }
  for (const policy of policyEvidence) {
    if (policy.signatureValid !== true) reasons.push(`policy ${String(policy.id)} signature is invalid`)
  }
  if (trustEvidence !== null && trustEvidence.valid !== true) {
    reasons.push(...(trustEvidence.errors as string[]).map((error) => `trust:${error}`))
  }
  if (profileEvidence !== null) {
    if (profileEvidence.valid !== true) {
      reasons.push(...(profileEvidence.errors as string[]).map((error) => `profile:${error}`))
    }
    const readiness = asMapping(profileEvidence.readiness)
    if (readiness.ready !== true) {
      for (const gap of asArray(readiness.gaps)) {
        if (isMapping(gap) && 'code' in gap) reasons.push(`profile:${String(gap.code)}`)
      }
    }
  }

  const artifact: Mapping = {
    schemaVersion: WORKFLOW_RELEASE_SCHEMA_VERSION,
    workflowId,
    generatedAt,
    release: sanitizeMetadata({ ...asMapping(manifest.release) }),
    passed: reasons.length === 0,
    reasons,
    prompts: {
      manifest: promptManifest === null ? null : promptManifestIdentity(promptManifest),
      releaseBundles: promptBundles,
    },
    evals: evalResults.map((result) => result.toJSON()),
    policies: policyEvidence,
    trust: trustEvidence,
    runtimeProfile: profileEvidence,
    metadata: sanitizeMetadata({ ...asMapping(manifest.metadata) }),
  }
  artifact.workflowHash = platformWorkflowReleaseHash(artifact)
  return { artifact, passed: reasons.length === 0, reasons }
}

/**
 * `sha256:<hex>` over the canonical JSON of a workflow release without
 * `workflowHash`, `evals[*].runner`, and `evals[*].source`.
 */
export function platformWorkflowReleaseHash(artifact: Mapping): string {
  const data: Mapping = { ...artifact }
  delete data.workflowHash
  if (Array.isArray(data.evals)) {
    data.evals = data.evals.map((item) =>
      isMapping(item)
        ? Object.fromEntries(Object.entries(item).filter(([key]) => !ENVIRONMENT_EVAL_KEYS.has(key)))
        : item,
    )
  }
  return `sha256:${createHash('sha256').update(canonicalJson(data), 'utf8').digest('hex')}`
}

/**
 * Cross-SDK canonical JSON: compact separators, object keys sorted by Unicode
 * code point, non-ASCII emitted as-is, control characters as lowercase
 * `\u00xx` escapes, integral numbers written as integers (`1.0` -> `1`), and
 * every other number as the shortest round-trip decimal without an exponent.
 * `undefined` object values are omitted, as `JSON.stringify` does.
 */
export function canonicalJson(value: unknown): string {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'string') return JSON.stringify(value)
  if (typeof value === 'number') return canonicalNumber(value)
  if (typeof value === 'bigint') return value.toString()
  if (Array.isArray(value)) return `[${value.map((item) => canonicalJson(item)).join(',')}]`
  if (typeof value === 'object') {
    const entries = Object.entries(value as Mapping)
      .filter(([, item]) => item !== undefined)
      .sort(([left], [right]) => compareCodePoints(left, right))
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(',')}}`
  }
  throw new TypeError(`cannot canonicalize ${typeof value}`)
}

function canonicalNumber(value: number): string {
  if (!Number.isFinite(value)) throw new RangeError('canonical JSON cannot represent NaN or Infinity')
  if (Number.isInteger(value)) return BigInt(value).toString()
  const text = String(value)
  if (!/e/i.test(text)) return text
  // Shortest digits come back in exponent form below 1e-6; expand them.
  const [mantissa, exponent] = text.toLowerCase().split('e') as [string, string]
  const negative = mantissa.startsWith('-')
  const [whole, fraction = ''] = (negative ? mantissa.slice(1) : mantissa).split('.') as [string, string?]
  const digits = whole + fraction
  const point = whole.length + Number(exponent)
  let plain: string
  if (point <= 0) plain = `0.${'0'.repeat(-point)}${digits}`
  else if (point >= digits.length) plain = digits + '0'.repeat(point - digits.length)
  else plain = `${digits.slice(0, point)}.${digits.slice(point)}`
  return negative ? `-${plain}` : plain
}

function compareCodePoints(left: string, right: string): number {
  const a = Array.from(left)
  const b = Array.from(right)
  const length = Math.min(a.length, b.length)
  for (let index = 0; index < length; index += 1) {
    const difference = a[index]!.codePointAt(0)! - b[index]!.codePointAt(0)!
    if (difference !== 0) return difference
  }
  return a.length - b.length
}

async function runEvalSections(
  manifest: Mapping,
  base: string,
  promptManifestPath: string | null,
): Promise<EvalRunResult[]> {
  const results: EvalRunResult[] = []
  for (const rawEval of asList(get(manifest, 'evals', get(manifest, 'eval')))) {
    const config: Mapping = typeof rawEval === 'string' ? { suite: rawEval } : { ...asMapping(rawEval) }
    const suitePath = requiredPath(base, config.suite, 'eval suite')
    const templates = stringList(get(config, 'templates', get(config, 'templateFiles')))
    const templatePath = get(config, 'template', get(config, 'templatesFile'))
    if (templatePath !== undefined && templatePath !== null) templates.push(String(templatePath))
    if (promptManifestPath !== null && templates.length === 0) templates.push(promptManifestPath)
    const baseline = config.baseline
    results.push(
      await runEvalFile(suitePath, {
        templatePaths: templates.map((value) => resolvePath(base, value)),
        failUnder: optionalFloat(get(config, 'failUnder', get(config, 'fail_under'))),
        baselinePath: baseline === undefined || baseline === null ? null : resolvePath(base, String(baseline)),
        maxRegression: Number(get(config, 'maxRegression', get(config, 'max_regression', 0))),
      }),
    )
  }
  return results
}

function buildPromptBundles(
  promptSection: Mapping,
  promptManifest: PromptManifest | null,
  reports: EvalRunResult['report'][],
  generatedAt: string,
): Mapping[] {
  if (promptManifest === null) return []
  let releases = asList(get(promptSection, 'releases', get(promptSection, 'release')))
  if (releases.length === 0 && truthy(get(promptSection, 'promptId', get(promptSection, 'prompt_id')))) {
    releases = [promptSection]
  }
  return releases.map((raw) => {
    const config = asMapping(raw)
    const fromVersion = get(config, 'fromVersion', get(config, 'from_version'))
    const bundleId = get(config, 'bundleId', get(config, 'bundle_id'))
    const bundle = buildPromptReleaseBundle({
      manifest: promptManifest,
      promptId: pyStr(get(config, 'promptId', get(config, 'prompt_id'))),
      promptVersion: pyStr(get(config, 'promptVersion', get(config, 'prompt_version'))),
      fromVersion: fromVersion === undefined || fromVersion === null ? null : String(fromVersion),
      reports,
      generatedAt: pyStr(get(config, 'generatedAt', generatedAt)),
      ...(bundleId === undefined || bundleId === null ? {} : { bundleId: String(bundleId) }),
      metadata: { ...asMapping(config.metadata) },
    })
    return wireBundle(bundle)
  })
}

/** Serialize a prompt release bundle with the Python wire shape (explicit nulls in gates). */
function wireBundle(bundle: PromptReleaseBundle): Mapping {
  return { ...bundle, gates: bundle.gates.map(wireGate) }
}

function policyEvidenceFor(manifest: Mapping, base: string): Mapping[] {
  return asList(get(manifest, 'policies', get(manifest, 'policyPacks'))).map((rawPolicy) => {
    const config: Mapping = typeof rawPolicy === 'string' ? { pathOrName: rawPolicy } : { ...asMapping(rawPolicy) }
    const pathOrName = pyStr(
      truthy(config.pathOrName) ??
        truthy(config.path_or_name) ??
        truthy(config.path) ??
        truthy(config.name) ??
        truthy(config.id),
    )
    const pack = loadPolicy(pathOrName, base)
    return {
      id: pyStr(truthy(config.id) ?? pack.id),
      name: pack.name,
      version: pack.version,
      domain: pack.domain,
      source: pathOrName,
      signatureValid: pack.verifySignature(),
      manifestDigest: sha256Json(pack.manifest()),
      detectorCount: pack.detectors.length,
      metadata: sanitizeMetadata({ ...asMapping(config.metadata) }),
    }
  })
}

function trustEvidenceFor(manifest: Mapping, base: string): Mapping | null {
  const raw = get(manifest, 'trustBundle', get(manifest, 'trust'))
  if (raw === undefined || raw === null) return null
  const bundle = loadSectionPayload(raw, base)
  const verification = verifyProductionTrustBundle(bundle)
  return {
    valid: verification.valid,
    errors: [...verification.errors],
    computedHash: verification.computedHash,
    bundle: sanitizeMetadata({ ...bundle }),
  }
}

function profileEvidenceFor(manifest: Mapping, base: string): Mapping | null {
  const raw = get(manifest, 'runtimeProfile', get(manifest, 'platformRuntimeProfile'))
  if (raw === undefined || raw === null) return null
  const profile = loadSectionPayload(raw, base)
  const verification = verifyPlatformRuntimeProfile(profile)
  return {
    valid: verification.valid,
    errors: [...verification.errors],
    computedHash: verification.computedHash,
    readiness: { ...verification.readiness },
    profile: sanitizeMetadata({ ...profile }),
  }
}

function loadSectionPayload(raw: unknown, base: string): Mapping {
  if (typeof raw === 'string') return loadEvalDocument(resolvePath(base, raw))
  const config = asMapping(raw)
  const path = config.path
  if (path !== undefined && path !== null) return loadEvalDocument(resolvePath(base, String(path)))
  const data = get(config, 'data', get(config, 'bundle', get(config, 'profile')))
  if (isMapping(data)) return { ...data }
  return { ...config }
}

function promptManifestIdentity(manifest: PromptManifest): Mapping {
  const record = manifest as unknown as Mapping
  const identity: Mapping = {
    schemaVersion: manifest.schemaVersion ?? null,
    registryId: manifest.registryId ?? null,
    digest: sha256Json(withoutKey(record, 'signature')),
  }
  const signature = record.signature
  if (isMapping(signature)) {
    identity.signature = Object.fromEntries(
      ['algorithm', 'keyId', 'value'].filter((key) => key in signature).map((key) => [key, signature[key]]),
    )
  }
  return identity
}

function loadPolicy(pathOrName: string, base: string) {
  const path = expandUser(pathOrName)
  const candidates = isAbsolute(path) ? [path] : [resolve(base, path), resolve(path)]
  for (const candidate of candidates) {
    if (existsSync(candidate)) return loadPolicyPackPath(candidate)
  }
  return loadPolicyPack(pathOrName)
}

function sanitizeMetadata(value: unknown): unknown {
  if (isMapping(value)) {
    const out: Mapping = {}
    for (const [key, item] of Object.entries(value)) {
      if (CONTENT_KEY_NAMES.has(normalizeKey(key))) {
        out[`${key}Hash`] = sha256Json(item)
        continue
      }
      out[key] = sanitizeMetadata(item)
    }
    return out
  }
  if (Array.isArray(value)) return value.map((item) => sanitizeMetadata(item))
  return value
}

function sha256Json(value: unknown): string {
  return createHash('sha256').update(canonicalJson(value), 'utf8').digest('hex')
}

function normalizeKey(value: string): string {
  return value.replace(/_/g, '').replace(/-/g, '').toLowerCase()
}

function withoutKey(value: Mapping, key: string): Mapping {
  return Object.fromEntries(Object.entries(value).filter(([name]) => name !== key))
}

/** `dict.get(key, default)`: a present key wins even when its value is null. */
function get(value: Mapping, key: string, fallback?: unknown): unknown {
  return Object.prototype.hasOwnProperty.call(value, key) ? value[key] : fallback
}

/** Python truthiness for the `a or b or c` chains the manifest accepts. */
function truthy(value: unknown): unknown {
  if (value === undefined || value === null || value === false || value === 0 || value === '') return undefined
  if (Array.isArray(value) && value.length === 0) return undefined
  if (isMapping(value) && Object.keys(value).length === 0) return undefined
  return value
}

function pyStr(value: unknown): string {
  if (value === undefined || value === null) return 'None'
  if (value === true) return 'True'
  if (value === false) return 'False'
  return String(value)
}

function utcNow(): string {
  return new Date().toISOString().replace(/\.\d{3}Z$/, '+00:00')
}

function expandUser(path: string): string {
  if (path === '~' || path.startsWith('~/')) return resolve(process.env.HOME ?? '', path.slice(2))
  return path
}

function resolvePath(base: string, value: string): string {
  const path = expandUser(value)
  return isAbsolute(path) ? path : resolve(base, path)
}

function optionalPath(base: string, value: unknown): string | null {
  return value === undefined || value === null ? null : resolvePath(base, String(value))
}

function requiredPath(base: string, value: unknown, label: string): string {
  if (value === undefined || value === null) {
    throw new Error(`platform workflow release requires ${label}`)
  }
  return resolvePath(base, String(value))
}

function optionalFloat(value: unknown): number | null {
  return value === undefined || value === null ? null : Number(value)
}

function asList(value: unknown): unknown[] {
  if (value === undefined || value === null) return []
  return Array.isArray(value) ? value : [value]
}

function stringList(value: unknown): string[] {
  if (value === undefined || value === null) return []
  return Array.isArray(value) ? value.map(String) : [String(value)]
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function asMapping(value: unknown): Mapping {
  return isMapping(value) ? value : {}
}

function isMapping(value: unknown): value is Mapping {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
