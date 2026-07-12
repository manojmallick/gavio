/** Prompt Registry + Evals foundation (F-EVAL-01/F-EVAL-02). */

import { createHash, createHmac } from 'node:crypto'
import { readFileSync } from 'node:fs'

import { GavioRequest } from '../request.js'
import { PromptLineage, Provider } from '../types.js'
import type { Message, PromptLineageInit, Provider as ProviderType } from '../types.js'

const PLACEHOLDER = /{{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*}}/g
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/

export const PROMPT_MANIFEST_SCHEMA_VERSION = 'gavio.prompt-registry.v2'
export const PROMPT_MANIFEST_SIGNATURE_ALGORITHM = 'HMAC-SHA256'

export interface PromptApprovalInit {
  status: string
  approvedBy?: string | null
  approvedAt?: string | null
  reviewers?: string[]
  reason?: string | null
  metadata?: Record<string, unknown>
}

export interface PromptManifestSignature {
  algorithm: typeof PROMPT_MANIFEST_SIGNATURE_ALGORITHM
  keyId: string
  value: string
}

export interface PromptManifest {
  schemaVersion?: string
  registryId?: string
  metadata?: Record<string, unknown>
  templates: PromptTemplateInit[]
  signature?: PromptManifestSignature
}

export interface PromptRegistryManifestOptions {
  verifySecret?: string | Buffer
  validateSemver?: boolean
}

export type PromptDiffChangeType = 'added' | 'removed' | 'changed'

export interface PromptDiffChange {
  path: string
  type: PromptDiffChangeType
  beforeHash?: string
  afterHash?: string
  before?: unknown
  after?: unknown
}

export interface PromptDiff {
  from: { id: string; version: string }
  to: { id: string; version: string }
  hasChanges: boolean
  changes: PromptDiffChange[]
}

export interface PromptTemplateInit {
  id: string
  version: string
  messages: Message[]
  requiredVariables?: string[]
  metadata?: Record<string, unknown>
  approval?: PromptApprovalInit | null
}

export interface RenderedPrompt {
  messages: Message[]
  lineage: PromptLineage
  toRequest(options: {
    model: string
    provider?: ProviderType | string
    metadata?: Record<string, unknown>
    options?: Record<string, unknown>
  }): GavioRequest
}

export class PromptTemplate {
  readonly id: string
  readonly version: string
  readonly messages: Message[]
  readonly requiredVariables: string[]
  readonly metadata: Record<string, unknown>
  readonly approval: PromptApprovalInit | null

  constructor(init: PromptTemplateInit) {
    this.id = init.id
    this.version = init.version
    this.messages = init.messages.map((message) => ({ ...message }))
    this.requiredVariables = [...(init.requiredVariables ?? [])]
    this.metadata = { ...(init.metadata ?? {}) }
    this.approval = init.approval === undefined || init.approval === null ? null : {
      status: init.approval.status,
      approvedBy: init.approval.approvedBy ?? undefined,
      approvedAt: init.approval.approvedAt ?? undefined,
      reviewers: [...(init.approval.reviewers ?? [])],
      reason: init.approval.reason ?? undefined,
      metadata: { ...(init.approval.metadata ?? {}) },
    }
  }

  placeholders(): Set<string> {
    const found = new Set<string>()
    for (const message of this.messages) {
      for (const value of Object.values(message)) {
        if (typeof value !== 'string') continue
        for (const match of value.matchAll(PLACEHOLDER)) found.add(match[1]!)
      }
    }
    return found
  }

  render(variables: Record<string, unknown>): RenderedPrompt {
    const required = new Set([...this.requiredVariables, ...this.placeholders()])
    const missing = [...required].filter((key) => !(key in variables)).sort()
    if (missing.length > 0) {
      throw new Error(`prompt template ${this.id}@${this.version} missing variables: ${missing.join(', ')}`)
    }
    const messages = this.messages.map((message) => {
      const rendered: Record<string, unknown> = {}
      for (const [key, value] of Object.entries(message)) {
        rendered[key] = renderValue(value, variables)
      }
      return rendered as Message
    })
    const lineage = new PromptLineage({
      templateId: this.id,
      templateVersion: this.version,
      variables: { ...variables },
      ragChunks: [],
    })
    return {
      messages,
      lineage,
      toRequest: (options) => new GavioRequest({
        messages: messages.map((message) => ({ ...message })),
        model: options.model,
        provider: options.provider ?? Provider.MOCK,
        metadata: options.metadata ?? {},
        options: options.options ?? {},
        lineage,
      }),
    }
  }

  toJSON(): PromptTemplateInit {
    const data: PromptTemplateInit = {
      id: this.id,
      version: this.version,
      messages: this.messages.map((message) => ({ ...message })),
      requiredVariables: [...this.requiredVariables],
      metadata: { ...this.metadata },
    }
    if (this.approval !== null) data.approval = cloneApproval(this.approval)
    return data
  }

  diff(other: PromptTemplate): PromptDiff {
    return diffPromptTemplates(this, other)
  }
}

export class PromptRegistry {
  private readonly templates = new Map<string, PromptTemplate>()
  private readonly latest = new Map<string, string>()

  constructor(templates: Array<PromptTemplate | PromptTemplateInit> = []) {
    for (const template of templates) this.register(template)
  }

  register(template: PromptTemplate | PromptTemplateInit): PromptTemplate {
    const parsed = template instanceof PromptTemplate ? template : new PromptTemplate(template)
    this.templates.set(key(parsed.id, parsed.version), parsed)
    this.latest.set(parsed.id, this.resolveLatestAfterRegister(parsed.id, parsed.version))
    return parsed
  }

  get(templateId: string, version?: string | null): PromptTemplate {
    const resolved = this.resolveVersion(templateId, version ?? null)
    if (resolved === undefined) throw new Error(`prompt template not found: ${templateId}@latest`)
    const template = this.templates.get(key(templateId, resolved))
    if (template === undefined) throw new Error(`prompt template not found: ${templateId}@${resolved}`)
    return template
  }

  render(templateId: string, variables: Record<string, unknown>, version?: string | null): RenderedPrompt {
    return this.get(templateId, version).render(variables)
  }

  versions(templateId: string): string[] {
    return [...this.templates.values()]
      .filter((template) => template.id === templateId)
      .map((template) => template.version)
      .sort(compareVersionStrings)
  }

  diff(templateId: string, fromVersion: string, toVersion?: string | null): PromptDiff {
    return this.get(templateId, fromVersion).diff(this.get(templateId, toVersion))
  }

  toManifest(options: {
    registryId?: string
    metadata?: Record<string, unknown>
    signSecret?: string | Buffer
    keyId?: string
  } = {}): PromptManifest {
    const manifest: PromptManifest = {
      schemaVersion: PROMPT_MANIFEST_SCHEMA_VERSION,
      registryId: options.registryId ?? 'default',
      metadata: { ...(options.metadata ?? {}) },
      templates: [...this.templates.values()]
        .sort((left, right) => left.id.localeCompare(right.id) || compareVersionStrings(left.version, right.version))
        .map((template) => template.toJSON()),
    }
    return options.signSecret === undefined
      ? manifest
      : signPromptManifest(manifest, options.signSecret, options.keyId ?? 'local')
  }

  static fromManifest(manifest: PromptManifest, options: PromptRegistryManifestOptions = {}): PromptRegistry {
    if (options.verifySecret !== undefined && !verifyPromptManifestSignature(manifest, options.verifySecret)) {
      throw new Error('prompt manifest signature verification failed')
    }
    const validate = options.validateSemver ?? manifest.schemaVersion === PROMPT_MANIFEST_SCHEMA_VERSION
    const registry = new PromptRegistry()
    for (const rawTemplate of manifest.templates) {
      if (validate) validateSemanticVersion(rawTemplate.version)
      registry.register(rawTemplate)
    }
    return registry
  }

  static fromFile(path: string | URL, options: PromptRegistryManifestOptions = {}): PromptRegistry {
    const manifest = JSON.parse(readFileSync(path, 'utf8')) as PromptManifest
    return PromptRegistry.fromManifest(manifest, options)
  }

  private resolveVersion(templateId: string, selector: string | null): string | undefined {
    if (selector === null || selector === 'latest') return this.latest.get(templateId)
    if (this.templates.has(key(templateId, selector))) return selector
    const candidates = [...this.templates.values()]
      .filter((template) => template.id === templateId && parseSemver(template.version) !== null)
      .map((template) => template.version)
      .sort(compareVersionStrings)
      .reverse()
    return candidates.find((candidate) => matchesSemverSelector(candidate, selector)) ?? selector
  }

  private resolveLatestAfterRegister(templateId: string, registered: string): string {
    const versions = [...this.templates.values()]
      .filter((template) => template.id === templateId)
      .map((template) => template.version)
    const semverVersions = versions.filter((version) => parseSemver(version) !== null)
    if (semverVersions.length === versions.length) {
      return semverVersions.sort(compareVersionStrings).at(-1) ?? registered
    }
    return registered
  }
}

export type EvalAssertionType = 'contains' | 'not_contains' | 'equals' | 'regex'

export interface EvalAssertionInit {
  type: EvalAssertionType
  value: unknown
  caseSensitive?: boolean
}

export interface EvalAssertionResult {
  type: EvalAssertionType
  passed: boolean
  expected: unknown
  reason: string
}

export class EvalAssertion {
  readonly type: EvalAssertionType
  readonly value: unknown
  readonly caseSensitive: boolean

  constructor(init: EvalAssertionInit) {
    this.type = init.type
    this.value = init.value
    this.caseSensitive = init.caseSensitive ?? false
  }

  check(output: string): EvalAssertionResult {
    const expected = String(this.value)
    let passed: boolean
    if (this.type === 'regex') {
      passed = new RegExp(expected).test(output)
    } else if (this.type === 'equals') {
      passed = cmp(output, expected, this.caseSensitive)
    } else if (this.type === 'not_contains') {
      passed = !haystack(output, this.caseSensitive).includes(needle(expected, this.caseSensitive))
    } else {
      passed = haystack(output, this.caseSensitive).includes(needle(expected, this.caseSensitive))
    }
    return {
      type: this.type,
      passed,
      expected: this.value,
      reason: passed ? 'passed' : `${this.type} assertion failed`,
    }
  }
}

export interface EvalCaseInit {
  id: string
  templateId: string
  templateVersion?: string | null
  variables: Record<string, unknown>
  assertions: EvalAssertionInit[]
  metadata?: Record<string, unknown>
  triage?: EvalFailureTriageInit | null
}

export interface EvalFailureTriageInit {
  category?: string | null
  severity?: string | null
  owner?: string | null
  action?: string | null
  notes?: string | null
  metadata?: Record<string, unknown>
}

export interface EvalFailureTriage {
  category?: string
  severity?: string
  owner?: string
  action?: string
  notes?: string
  metadata?: Record<string, unknown>
}

export class EvalCase {
  readonly id: string
  readonly templateId: string
  readonly templateVersion: string | null
  readonly variables: Record<string, unknown>
  readonly assertions: EvalAssertion[]
  readonly metadata: Record<string, unknown>
  readonly triage: EvalFailureTriage | null

  constructor(init: EvalCaseInit) {
    this.id = init.id
    this.templateId = init.templateId
    this.templateVersion = init.templateVersion ?? null
    this.variables = { ...init.variables }
    this.assertions = init.assertions.map((assertion) => new EvalAssertion(assertion))
    this.metadata = sanitizeWorkflowMetadata(init.metadata ?? {})
    const triage = init.triage ?? (isRecord(init.metadata?.triage) ? init.metadata.triage : null)
    this.triage = triage === null ? null : normalizeTriage(triage as EvalFailureTriageInit)
  }
}

export interface EvalCaseResult {
  id: string
  templateId: string
  templateVersion: string
  passed: boolean
  score: number
  outputHash: string
  assertions: EvalAssertionResult[]
  lineage: PromptLineageInit
  triage?: EvalFailureTriage
}

export interface EvalReport {
  suiteId: string
  totalCases: number
  passedCases: number
  failedCases: number
  score: number
  cases: EvalCaseResult[]
}

export type CompletionFn = (
  prompt: RenderedPrompt,
  testCase: EvalCase,
) => string | Promise<string>

export class EvalSuite {
  readonly id: string
  readonly cases: EvalCase[]

  constructor(init: { id: string; cases: Array<EvalCase | EvalCaseInit> }) {
    this.id = init.id
    this.cases = init.cases.map((testCase) => testCase instanceof EvalCase ? testCase : new EvalCase(testCase))
  }

  async run(registry: PromptRegistry, complete: CompletionFn): Promise<EvalReport> {
    const cases: EvalCaseResult[] = []
    for (const testCase of this.cases) {
      const prompt = registry.render(testCase.templateId, testCase.variables, testCase.templateVersion)
      const output = await complete(prompt, testCase)
      const assertions = testCase.assertions.map((assertion) => assertion.check(output))
      const passed = assertions.every((assertion) => assertion.passed)
      const score = assertions.length === 0
        ? 0
        : round8(assertions.filter((assertion) => assertion.passed).length / assertions.length)
      const result: EvalCaseResult = {
        id: testCase.id,
        templateId: testCase.templateId,
        templateVersion: prompt.lineage.templateVersion ?? '',
        passed,
        score,
        outputHash: createHash('sha256').update(output, 'utf8').digest('hex'),
        assertions,
        lineage: prompt.lineage.toJSON(),
      }
      if (!passed && testCase.triage !== null) result.triage = testCase.triage
      cases.push(result)
    }
    const passedCases = cases.filter((testCase) => testCase.passed).length
    return {
      suiteId: this.id,
      totalCases: cases.length,
      passedCases,
      failedCases: cases.length - passedCases,
      score: cases.length === 0 ? 0 : round8(cases.reduce((sum, c) => sum + c.score, 0) / cases.length),
      cases,
    }
  }
}

export interface PromptEvalLinkInit {
  promptId?: string
  templateId?: string
  promptVersion?: string
  templateVersion?: string
  suiteId?: string
  evalSuiteId?: string
  baselineScore?: number | null
  failUnder?: number | null
  maxRegression?: number
  metadata?: Record<string, unknown>
}

export interface PromptEvalLink {
  promptId: string
  promptVersion: string
  suiteId: string
  baselineScore?: number | null
  failUnder?: number | null
  maxRegression: number
  metadata?: Record<string, unknown>
}

export interface PromptVersionGate {
  promptId: string
  promptVersion: string
  suiteId: string
  passed: boolean
  score: number
  totalCases: number
  passedCases: number
  failedCases: string[]
  reasons: string[]
  baselineScore?: number | null
  failUnder?: number | null
  maxRegression: number
  scoreDelta?: number | null
}

export interface PromptWorkflowResult {
  passed: boolean
  links: PromptEvalLink[]
  gates: PromptVersionGate[]
}

export interface PromptReleaseBundle {
  schemaVersion: 'gavio.prompt-release-bundle.v1'
  bundleId: string
  prompt: { id: string; version: string }
  generatedAt: string
  manifest: Record<string, unknown>
  passed: boolean
  gates: PromptVersionGate[]
  evalReports: EvalReport[]
  promptDiff?: PromptDiff
  metadata?: Record<string, unknown>
}

export function promptEvalLink(init: PromptEvalLinkInit): PromptEvalLink {
  const promptId = init.promptId ?? init.templateId
  const promptVersion = init.promptVersion ?? init.templateVersion
  const suiteId = init.suiteId ?? init.evalSuiteId
  if (promptId === undefined || promptVersion === undefined || suiteId === undefined) {
    throw new Error('prompt eval link requires promptId, promptVersion, and suiteId')
  }
  const link: PromptEvalLink = {
    promptId,
    promptVersion,
    suiteId,
    maxRegression: init.maxRegression ?? 0,
  }
  if (init.baselineScore !== undefined) link.baselineScore = init.baselineScore
  if (init.failUnder !== undefined) link.failUnder = init.failUnder
  if (init.metadata !== undefined && Object.keys(init.metadata).length > 0) {
    link.metadata = sanitizeWorkflowMetadata(init.metadata)
  }
  return link
}

export function evaluatePromptVersionGate(report: EvalReport, link: PromptEvalLink | PromptEvalLinkInit): PromptVersionGate {
  const parsed = 'promptId' in link && 'promptVersion' in link && 'suiteId' in link
    ? link as PromptEvalLink
    : promptEvalLink(link)
  const cases = report.suiteId === parsed.suiteId
    ? report.cases.filter((testCase) => testCase.templateId === parsed.promptId && testCase.templateVersion === parsed.promptVersion)
    : []
  const failedCases = cases.filter((testCase) => !testCase.passed).map((testCase) => testCase.id)
  const score = cases.length === 0 ? 0 : round8(cases.reduce((sum, testCase) => sum + testCase.score, 0) / cases.length)
  const reasons: string[] = []
  if (cases.length === 0) reasons.push(`no eval cases found for ${parsed.promptId}@${parsed.promptVersion} in ${parsed.suiteId}`)
  if (failedCases.length > 0) reasons.push(`${failedCases.length} linked eval case(s) failed`)
  if (parsed.failUnder !== undefined && parsed.failUnder !== null && score < parsed.failUnder) {
    reasons.push(`score ${score.toFixed(8)} is below fail-under ${parsed.failUnder.toFixed(8)}`)
  }
  let scoreDelta: number | null | undefined
  if (parsed.baselineScore !== undefined && parsed.baselineScore !== null) {
    scoreDelta = round8(score - parsed.baselineScore)
    if (scoreDelta < -parsed.maxRegression) {
      reasons.push(`score regression ${scoreDelta.toFixed(8)} exceeds allowed ${parsed.maxRegression.toFixed(8)}`)
    }
  }
  return {
    promptId: parsed.promptId,
    promptVersion: parsed.promptVersion,
    suiteId: parsed.suiteId,
    passed: reasons.length === 0,
    score,
    totalCases: cases.length,
    passedCases: cases.filter((testCase) => testCase.passed).length,
    failedCases,
    reasons,
    baselineScore: parsed.baselineScore,
    failUnder: parsed.failUnder,
    maxRegression: parsed.maxRegression,
    scoreDelta,
  }
}

export function evaluatePromptWorkflow(report: EvalReport, links: Array<PromptEvalLink | PromptEvalLinkInit>): PromptWorkflowResult {
  const parsedLinks = links.map((link) => 'promptId' in link && 'promptVersion' in link && 'suiteId' in link
    ? link as PromptEvalLink
    : promptEvalLink(link))
  const gates = parsedLinks.map((link) => evaluatePromptVersionGate(report, link))
  return { passed: gates.every((gate) => gate.passed), links: parsedLinks, gates }
}

export function promptEvalLinksFromManifest(manifest: PromptManifest): PromptEvalLink[] {
  const links: PromptEvalLink[] = []
  const manifestLinks = (manifest as PromptManifest & { promptEvalLinks?: unknown[]; evalLinks?: unknown[] }).promptEvalLinks
    ?? (manifest as PromptManifest & { evalLinks?: unknown[] }).evalLinks
    ?? []
  for (const raw of manifestLinks) {
    if (isRecord(raw)) links.push(promptEvalLink(raw as PromptEvalLinkInit))
  }
  for (const template of manifest.templates) {
    const candidates: unknown[] = []
    for (const keyName of ['promptEvalLinks', 'evalLinks', 'evals']) {
      const value = (template as unknown as Record<string, unknown>)[keyName]
      if (Array.isArray(value)) candidates.push(...value)
    }
    for (const keyName of ['promptEvalLinks', 'evalLinks', 'evals']) {
      const value = template.metadata?.[keyName]
      if (Array.isArray(value)) candidates.push(...value)
    }
    for (const raw of candidates) {
      if (!isRecord(raw)) continue
      links.push(promptEvalLink({
        ...raw,
        promptId: (raw.promptId as string | undefined) ?? (raw.templateId as string | undefined) ?? template.id,
        promptVersion: (raw.promptVersion as string | undefined) ?? (raw.templateVersion as string | undefined) ?? template.version,
      }))
    }
  }
  return links
}

export function buildPromptReleaseBundle(options: {
  manifest: PromptManifest
  promptId: string
  promptVersion: string
  reports: EvalReport[]
  links?: Array<PromptEvalLink | PromptEvalLinkInit>
  fromVersion?: string | null
  generatedAt?: string
  bundleId?: string
  metadata?: Record<string, unknown>
}): PromptReleaseBundle {
  const registry = PromptRegistry.fromManifest(options.manifest, { validateSemver: false })
  const target = registry.get(options.promptId, options.promptVersion)
  const versions = registry.versions(options.promptId).filter((version) => version !== target.version)
  const fromVersion = options.fromVersion ?? versions.at(-1) ?? null
  const diff = fromVersion === null ? undefined : registry.diff(options.promptId, fromVersion, target.version)
  const links = (options.links ?? promptEvalLinksFromManifest(options.manifest))
    .map((link) => 'promptId' in link && 'promptVersion' in link && 'suiteId' in link ? link as PromptEvalLink : promptEvalLink(link))
    .filter((link) => link.promptId === options.promptId && link.promptVersion === options.promptVersion)
  const gates = options.reports.flatMap((report) => links
    .filter((link) => link.suiteId === report.suiteId)
    .map((link) => evaluatePromptVersionGate(report, link)))
  const bundle: PromptReleaseBundle = {
    schemaVersion: 'gavio.prompt-release-bundle.v1',
    bundleId: options.bundleId ?? `${options.promptId}@${options.promptVersion}`,
    prompt: { id: options.promptId, version: options.promptVersion },
    generatedAt: options.generatedAt ?? new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    manifest: manifestIdentity(options.manifest),
    passed: gates.every((gate) => gate.passed),
    gates,
    evalReports: options.reports,
  }
  if (diff !== undefined) bundle.promptDiff = diff
  if (options.metadata !== undefined && Object.keys(options.metadata).length > 0) {
    bundle.metadata = sanitizeWorkflowMetadata(options.metadata)
  }
  return bundle
}

function key(id: string, version: string): string {
  return `${id}@${version}`
}

function renderValue(value: unknown, variables: Record<string, unknown>): unknown {
  if (typeof value !== 'string') return value
  return value.replace(PLACEHOLDER, (_match, name: string) => String(variables[name]))
}

function cmp(left: string, right: string, caseSensitive: boolean): boolean {
  return caseSensitive ? left === right : left.toLowerCase() === right.toLowerCase()
}

function haystack(value: string, caseSensitive: boolean): string {
  return caseSensitive ? value : value.toLowerCase()
}

function needle(value: string, caseSensitive: boolean): string {
  return caseSensitive ? value : value.toLowerCase()
}

function round8(value: number): number {
  return Math.round(value * 1e8) / 1e8
}

export function diffPromptTemplates(before: PromptTemplate, after: PromptTemplate): PromptDiff {
  const changes: PromptDiffChange[] = []
  const maxMessages = Math.max(before.messages.length, after.messages.length)
  for (let idx = 0; idx < maxMessages; idx += 1) {
    const path = `messages[${idx}]`
    const beforeMessage = before.messages[idx]
    const afterMessage = after.messages[idx]
    if (beforeMessage === undefined && afterMessage !== undefined) {
      changes.push({ path, type: 'added', afterHash: sha256Json(afterMessage) })
      continue
    }
    if (beforeMessage !== undefined && afterMessage === undefined) {
      changes.push({ path, type: 'removed', beforeHash: sha256Json(beforeMessage) })
      continue
    }
    if (beforeMessage === undefined || afterMessage === undefined) continue
    for (const messageKey of [...new Set([...Object.keys(beforeMessage), ...Object.keys(afterMessage)])].sort()) {
      if ((beforeMessage as Record<string, unknown>)[messageKey] === (afterMessage as Record<string, unknown>)[messageKey]) {
        continue
      }
      changes.push({
        path: `${path}.${messageKey}`,
        type: changeType(messageKey in beforeMessage, messageKey in afterMessage),
        beforeHash: messageKey in beforeMessage ? sha256Json((beforeMessage as Record<string, unknown>)[messageKey]) : undefined,
        afterHash: messageKey in afterMessage ? sha256Json((afterMessage as Record<string, unknown>)[messageKey]) : undefined,
      })
    }
  }

  const beforeRequired = new Set(before.requiredVariables)
  const afterRequired = new Set(after.requiredVariables)
  if (!sameStringSet(beforeRequired, afterRequired)) {
    changes.push({
      path: 'requiredVariables',
      type: 'changed',
      before: [...beforeRequired].filter((item) => !afterRequired.has(item)).sort(),
      after: [...afterRequired].filter((item) => !beforeRequired.has(item)).sort(),
    })
  }
  diffRecord('metadata', before.metadata, after.metadata, changes)
  diffRecord('approval', approvalRecord(before.approval), approvalRecord(after.approval), changes)
  return {
    from: { id: before.id, version: before.version },
    to: { id: after.id, version: after.version },
    hasChanges: changes.length > 0,
    changes,
  }
}

export function validateSemanticVersion(version: string): boolean {
  if (parseSemver(version) === null) throw new Error(`invalid semantic version: ${version}`)
  return true
}

export function signPromptManifest(
  manifest: PromptManifest,
  secret: string | Buffer,
  keyId = 'local',
): PromptManifest {
  return {
    ...manifest,
    signature: {
      algorithm: PROMPT_MANIFEST_SIGNATURE_ALGORITHM,
      keyId,
      value: promptManifestSignatureValue(manifest, secret),
    },
  }
}

export function verifyPromptManifestSignature(manifest: PromptManifest, secret: string | Buffer): boolean {
  const signature = manifest.signature
  if (signature === undefined) return false
  if (signature.algorithm !== PROMPT_MANIFEST_SIGNATURE_ALGORITHM) return false
  return signature.value === promptManifestSignatureValue(manifest, secret)
}

export function promptManifestDigest(manifest: PromptManifest): string {
  return createHash('sha256').update(canonicalPromptManifest(manifest), 'utf8').digest('hex')
}

export function canonicalPromptManifest(manifest: PromptManifest): string {
  const unsigned = { ...manifest }
  delete unsigned.signature
  return JSON.stringify(canonicalize(unsigned))
}

function promptManifestSignatureValue(manifest: PromptManifest, secret: string | Buffer): string {
  return createHmac('sha256', secret).update(canonicalPromptManifest(manifest), 'utf8').digest('hex')
}

function cloneApproval(approval: PromptApprovalInit): PromptApprovalInit {
  const cloned: PromptApprovalInit = {
    status: approval.status,
    reviewers: [...(approval.reviewers ?? [])],
  }
  if (approval.approvedBy !== undefined && approval.approvedBy !== null) cloned.approvedBy = approval.approvedBy
  if (approval.approvedAt !== undefined && approval.approvedAt !== null) cloned.approvedAt = approval.approvedAt
  if (approval.reason !== undefined && approval.reason !== null) cloned.reason = approval.reason
  if (approval.metadata !== undefined && Object.keys(approval.metadata).length > 0) {
    cloned.metadata = { ...approval.metadata }
  }
  return cloned
}

const CONTENT_METADATA_KEYS = new Set([
  'content',
  'completion',
  'messages',
  'output',
  'prompt',
  'raw',
  'rawOutput',
  'renderedPrompt',
  'response',
  'text',
])

function normalizeTriage(init: EvalFailureTriageInit): EvalFailureTriage {
  const triage: EvalFailureTriage = {}
  if (init.category !== undefined && init.category !== null) triage.category = init.category
  if (init.severity !== undefined && init.severity !== null) triage.severity = init.severity
  if (init.owner !== undefined && init.owner !== null) triage.owner = init.owner
  if (init.action !== undefined && init.action !== null) triage.action = init.action
  if (init.notes !== undefined && init.notes !== null) triage.notes = init.notes
  if (init.metadata !== undefined && Object.keys(init.metadata).length > 0) {
    triage.metadata = sanitizeWorkflowMetadata(init.metadata)
  }
  return triage
}

function sanitizeWorkflowMetadata(data: Record<string, unknown>): Record<string, unknown> {
  const sanitized: Record<string, unknown> = {}
  for (const [recordKey, value] of Object.entries(data)) {
    if (CONTENT_METADATA_KEYS.has(recordKey)) {
      sanitized[`${recordKey}Hash`] = sha256Json(value)
    } else {
      sanitized[recordKey] = sanitizeWorkflowValue(value)
    }
  }
  return sanitized
}

function sanitizeWorkflowValue(value: unknown): unknown {
  if (isRecord(value)) return sanitizeWorkflowMetadata(value)
  if (Array.isArray(value)) return value.map((item) => sanitizeWorkflowValue(item))
  return value
}

function manifestIdentity(manifest: PromptManifest): Record<string, unknown> {
  const identity: Record<string, unknown> = {
    schemaVersion: manifest.schemaVersion,
    registryId: manifest.registryId,
    digest: promptManifestDigest(manifest),
  }
  if (manifest.signature !== undefined) {
    identity.signature = {
      algorithm: manifest.signature.algorithm,
      keyId: manifest.signature.keyId,
      value: manifest.signature.value,
    }
  }
  return identity
}

function approvalRecord(approval: PromptApprovalInit | null): Record<string, unknown> {
  return approval === null ? {} : cloneApproval(approval) as unknown as Record<string, unknown>
}

function diffRecord(
  prefix: string,
  before: Record<string, unknown>,
  after: Record<string, unknown>,
  changes: PromptDiffChange[],
): void {
  for (const recordKey of [...new Set([...Object.keys(before), ...Object.keys(after)])].sort()) {
    if (JSON.stringify(before[recordKey]) === JSON.stringify(after[recordKey])) continue
    const contentKey = CONTENT_METADATA_KEYS.has(recordKey)
    const change: PromptDiffChange = {
      path: `${prefix}.${recordKey}`,
      type: changeType(recordKey in before, recordKey in after),
    }
    if (contentKey) {
      if (recordKey in before) change.beforeHash = sha256Json(before[recordKey])
      if (recordKey in after) change.afterHash = sha256Json(after[recordKey])
    } else {
      if (recordKey in before) change.before = sanitizeWorkflowValue(before[recordKey])
      if (recordKey in after) change.after = sanitizeWorkflowValue(after[recordKey])
    }
    changes.push(change)
  }
}

function changeType(hasBefore: boolean, hasAfter: boolean): PromptDiffChangeType {
  if (hasBefore && hasAfter) return 'changed'
  return hasAfter ? 'added' : 'removed'
}

function sameStringSet(left: Set<string>, right: Set<string>): boolean {
  return left.size === right.size && [...left].every((item) => right.has(item))
}

function sha256Json(value: unknown): string {
  return createHash('sha256').update(JSON.stringify(canonicalize(value)), 'utf8').digest('hex')
}

function canonicalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((item) => canonicalize(item))
  if (isRecord(value)) {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((recordKey) => [recordKey, canonicalize(value[recordKey])]),
    )
  }
  return value
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

interface SemVersion {
  major: number
  minor: number
  patch: number
  prerelease: string | null
}

function parseSemver(version: string): SemVersion | null {
  const match = version.match(SEMVER)
  if (match === null) return null
  return {
    major: Number(match[1]),
    minor: Number(match[2]),
    patch: Number(match[3]),
    prerelease: match[4] ?? null,
  }
}

function compareVersionStrings(left: string, right: string): number {
  const leftParsed = parseSemver(left)
  const rightParsed = parseSemver(right)
  if (leftParsed === null || rightParsed === null) return left.localeCompare(right)
  return compareSemver(leftParsed, rightParsed)
}

function compareSemver(left: SemVersion, right: SemVersion): number {
  for (const keyName of ['major', 'minor', 'patch'] as const) {
    if (left[keyName] !== right[keyName]) return left[keyName] - right[keyName]
  }
  if (left.prerelease === right.prerelease) return 0
  if (left.prerelease === null) return 1
  if (right.prerelease === null) return -1
  return left.prerelease.localeCompare(right.prerelease)
}

function matchesSemverSelector(version: string, selector: string): boolean {
  const parsed = parseSemver(version)
  if (parsed === null) return false
  if (selector === '*' || selector === 'latest') return true
  if (selector.startsWith('^')) {
    const base = parseSemver(selector.slice(1))
    if (base === null || compareSemver(parsed, base) < 0) return false
    if (base.major > 0) return parsed.major === base.major
    if (base.minor > 0) return parsed.major === 0 && parsed.minor === base.minor
    return parsed.major === base.major && parsed.minor === base.minor && parsed.patch === base.patch
  }
  if (selector.startsWith('~')) {
    const base = parseSemver(selector.slice(1))
    return base !== null
      && compareSemver(parsed, base) >= 0
      && parsed.major === base.major
      && parsed.minor === base.minor
  }
  const constraints = selector.split(/\s+/).filter((item) => item.length > 0)
  return constraints.length > 0 && constraints.every((constraint) => matchesConstraint(parsed, constraint))
}

function matchesConstraint(version: SemVersion, constraint: string): boolean {
  for (const operator of ['>=', '<=', '>', '<', '='] as const) {
    if (!constraint.startsWith(operator)) continue
    const base = parseSemver(constraint.slice(operator.length))
    if (base === null) return false
    const cmp = compareSemver(version, base)
    if (operator === '>=') return cmp >= 0
    if (operator === '<=') return cmp <= 0
    if (operator === '>') return cmp > 0
    if (operator === '<') return cmp < 0
    return cmp === 0
  }
  const base = parseSemver(constraint)
  return base !== null && compareSemver(version, base) === 0
}
