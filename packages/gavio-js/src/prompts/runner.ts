/**
 * File-backed deterministic eval runner and CI gate.
 *
 * Library port of `gavio.prompts.runner` (Python). Every case must provide a
 * deterministic output through a case-level output key or a top-level
 * `outputs` map; provider calls are left to application code that uses
 * {@link EvalSuite} directly. The `gavio eval run` CLI stays Python-only, and
 * only JSON documents are read here (YAML suites are Python-only).
 */

import { readFileSync } from 'node:fs'
import { dirname, isAbsolute, resolve } from 'node:path'

import { VERSION } from '../version.js'
import {
  EvalSuite,
  PromptRegistry,
  evaluatePromptWorkflow,
  promptEvalLink,
} from './index.js'
import type {
  EvalCaseInit,
  EvalReport,
  PromptEvalLink,
  PromptEvalLinkInit,
  PromptTemplateInit,
  PromptVersionGate,
  PromptWorkflowResult,
} from './index.js'

export const EVAL_RUNNER_SCHEMA_VERSION = '1.0'

const OUTPUT_KEYS = ['mockOutput', 'mock_output', 'output', 'expectedOutput', 'expected_output']

export interface EvalGate {
  passed: boolean
  reasons: string[]
  failUnder: number | null
  baselineScore: number | null
  maxRegression: number
  scoreDelta: number | null
}

export interface EvalGateOptions {
  failUnder?: number | null
  baselineScore?: number | null
  maxRegression?: number
}

export interface RunEvalFileOptions {
  templatePaths?: string[]
  failUnder?: number | null
  baselinePath?: string | null
  maxRegression?: number
}

export class EvalRunResult {
  constructor(
    readonly report: EvalReport,
    readonly gate: EvalGate,
    readonly source: string,
    readonly workflow: PromptWorkflowResult | null = null,
  ) {}

  get passed(): boolean {
    return this.gate.passed && (this.workflow === null || this.workflow.passed)
  }

  toJSON(): Record<string, unknown> {
    const data: Record<string, unknown> = {
      ...this.report,
      schemaVersion: EVAL_RUNNER_SCHEMA_VERSION,
      runner: { name: 'gavio-eval-runner', version: VERSION, node: process.versions.node },
      source: this.source,
      gate: { ...this.gate },
    }
    if (this.workflow !== null) {
      data.workflow = {
        passed: this.workflow.passed,
        links: this.workflow.links,
        gates: this.workflow.gates.map(wireGate),
      }
    }
    return data
  }
}

/** Gate fields always serialize, using `null` for "not configured" (Python wire shape). */
export function wireGate(gate: PromptVersionGate): Record<string, unknown> {
  return {
    ...gate,
    baselineScore: gate.baselineScore ?? null,
    failUnder: gate.failUnder ?? null,
    scoreDelta: gate.scoreDelta ?? null,
  }
}

/** Load a JSON eval document; the top level must be an object. */
export function loadEvalDocument(path: string): Record<string, unknown> {
  const data: unknown = JSON.parse(readFileSync(expandUser(path), 'utf8'))
  if (!isRecord(data)) throw new Error(`${path}: expected a JSON object`)
  return data
}

/** Run one deterministic eval suite file and evaluate its CI gate. */
export async function runEvalFile(
  suitePath: string,
  options: RunEvalFileOptions = {},
): Promise<EvalRunResult> {
  const suiteFile = resolve(expandUser(suitePath))
  const payload = loadEvalDocument(suiteFile)
  const templates = loadTemplates(suiteFile, payload, options.templatePaths ?? [])
  const registry = new PromptRegistry(templates)
  const suite = buildSuite(suitePayload(payload))
  const outputs = outputMap(payload, suite)
  const report = await suite.run(registry, (_prompt, testCase) => outputs.get(testCase.id) as string)
  const baselineScore =
    options.baselinePath === undefined || options.baselinePath === null
      ? null
      : loadBaselineScore(options.baselinePath)
  const gate = evaluateGate(report, {
    failUnder: options.failUnder ?? null,
    baselineScore,
    maxRegression: options.maxRegression ?? 0,
  })
  const links = workflowLinks(payload, templates, suite.id)
  const workflow = links.length > 0 ? evaluatePromptWorkflow(report, links) : null
  return new EvalRunResult(report, gate, suiteFile, workflow)
}

export function evaluateGate(report: EvalReport, options: EvalGateOptions = {}): EvalGate {
  const failUnder = options.failUnder ?? null
  const baselineScore = options.baselineScore ?? null
  const maxRegression = options.maxRegression ?? 0
  const reasons: string[] = []
  if (report.failedCases > 0) reasons.push(`${report.failedCases} eval case(s) failed`)
  if (failUnder !== null && report.score < failUnder) {
    reasons.push(`score ${report.score.toFixed(8)} is below fail-under ${failUnder.toFixed(8)}`)
  }
  let scoreDelta: number | null = null
  if (baselineScore !== null) {
    scoreDelta = round8(report.score - baselineScore)
    if (scoreDelta < -maxRegression) {
      reasons.push(
        `score regression ${scoreDelta.toFixed(8)} exceeds allowed ${maxRegression.toFixed(8)}`,
      )
    }
  }
  return { passed: reasons.length === 0, reasons, failUnder, baselineScore, maxRegression, scoreDelta }
}

function suitePayload(payload: Record<string, unknown>): Record<string, unknown> {
  const suite = payload.suite
  if (suite !== undefined && suite !== null) {
    if (!isRecord(suite)) throw new Error('suite must be an object')
    return suite
  }
  if (!('id' in payload) || !('cases' in payload)) {
    throw new Error('eval document must contain either suite or id/cases')
  }
  return { id: payload.id, cases: payload.cases }
}

function buildSuite(data: Record<string, unknown>): EvalSuite {
  const cases = asArray(data.cases).map((raw) => caseInit(asRecord(raw)))
  return new EvalSuite({ id: String(data.id), cases })
}

function caseInit(data: Record<string, unknown>): EvalCaseInit {
  const init: EvalCaseInit = {
    id: String(data.id),
    templateId: String(data.templateId),
    templateVersion: data.templateVersion === undefined || data.templateVersion === null
      ? null
      : String(data.templateVersion),
    variables: asRecord(data.variables),
    assertions: asArray(data.assertions).map((raw) => {
      const item = asRecord(raw)
      return {
        type: String(item.type) as 'contains',
        value: item.value ?? null,
        caseSensitive: Boolean(item.caseSensitive ?? item.case_sensitive ?? false),
      }
    }),
    metadata: asRecord(data.metadata),
  }
  if (isRecord(data.triage)) init.triage = data.triage
  return init
}

function loadTemplates(
  suiteFile: string,
  payload: Record<string, unknown>,
  templatePaths: string[],
): PromptTemplateInit[] {
  const templates: PromptTemplateInit[] = []
  const inline = payload.templates
  if (inline !== undefined && inline !== null) {
    if (!Array.isArray(inline)) throw new Error('templates must be a list')
    templates.push(...(inline as PromptTemplateInit[]))
  }

  const paths: string[] = [...templatePaths]
  for (const key of ['templatesFile', 'templates_file', 'templateFile', 'template_file']) {
    const value = payload[key]
    if (value !== undefined && value !== null) paths.push(String(value))
  }
  for (const key of ['templateFiles', 'template_files']) {
    const value = payload[key]
    if (Array.isArray(value)) paths.push(...value.map(String))
  }

  for (const path of paths) {
    const templatePayload = loadEvalDocument(resolveRelative(suiteFile, path))
    let items: unknown = templatePayload.templates ?? templatePayload
    if (isRecord(items)) items = [items]
    if (!Array.isArray(items)) {
      throw new Error(`${path}: expected template object/list or templates list`)
    }
    templates.push(...(items as PromptTemplateInit[]))
  }

  if (templates.length === 0) throw new Error('eval document must provide templates or template files')
  return templates
}

function workflowLinks(
  payload: Record<string, unknown>,
  templates: PromptTemplateInit[],
  suiteId: string,
): PromptEvalLink[] {
  const links: PromptEvalLink[] = []
  const push = (raw: unknown, defaults: { promptId?: string; promptVersion?: string }): void => {
    if (isRecord(raw)) links.push(linkFromDict(raw, { ...defaults, suiteId }))
  }

  const workflow = payload.promptWorkflow
  if (isRecord(workflow)) {
    const rawLinks = workflow.links ?? workflow.promptEvalLinks
    if (Array.isArray(rawLinks)) for (const raw of rawLinks) push(raw, {})
  }
  for (const key of ['promptEvalLinks', 'evalLinks']) {
    const rawLinks = payload[key]
    if (Array.isArray(rawLinks)) for (const raw of rawLinks) push(raw, {})
  }
  for (const template of templates) {
    const record = template as unknown as Record<string, unknown>
    const defaults = { promptId: String(record.id), promptVersion: String(record.version) }
    const candidates: unknown[] = []
    for (const key of ['promptEvalLinks', 'evalLinks', 'evals']) {
      const value = record[key]
      if (Array.isArray(value)) candidates.push(...value)
    }
    const metadata = record.metadata
    if (isRecord(metadata)) {
      for (const key of ['promptEvalLinks', 'evalLinks', 'evals']) {
        const value = metadata[key]
        if (Array.isArray(value)) candidates.push(...value)
      }
    }
    for (const raw of candidates) push(raw, defaults)
  }

  // Later links win but keep the position of the first one, like a dict.
  const deduped = new Map<string, PromptEvalLink>()
  for (const link of links) {
    deduped.set(JSON.stringify([link.promptId, link.promptVersion, link.suiteId]), link)
  }
  return [...deduped.values()]
}

function linkFromDict(
  data: Record<string, unknown>,
  defaults: { promptId?: string; promptVersion?: string; suiteId?: string },
): PromptEvalLink {
  const promptId = data.promptId ?? data.templateId ?? defaults.promptId
  const promptVersion = data.promptVersion ?? data.templateVersion ?? defaults.promptVersion
  const suiteId = data.suiteId ?? data.evalSuiteId ?? data.id ?? defaults.suiteId
  if (promptId == null || promptVersion == null || suiteId == null) {
    throw new Error('prompt eval link requires promptId, promptVersion, and suiteId')
  }
  const baseline = data.baselineScore ?? data.baseline_score
  const failUnder = data.failUnder ?? data.fail_under
  const init: PromptEvalLinkInit = {
    promptId: String(promptId),
    promptVersion: String(promptVersion),
    suiteId: String(suiteId),
    maxRegression: Number(data.maxRegression ?? data.max_regression ?? 0),
  }
  if (baseline != null) init.baselineScore = Number(baseline)
  if (failUnder != null) init.failUnder = Number(failUnder)
  if (isRecord(data.metadata)) init.metadata = data.metadata
  return promptEvalLink(init)
}

function outputMap(payload: Record<string, unknown>, suite: EvalSuite): Map<string, string> {
  const outputs = new Map<string, string>()
  const rawOutputs = firstTruthy(payload.outputs, payload.mockOutputs, payload.mock_outputs)
  if (isRecord(rawOutputs)) {
    for (const [key, value] of Object.entries(rawOutputs)) outputs.set(key, String(value))
  }
  for (const raw of asArray(suitePayload(payload).cases)) {
    if (!isRecord(raw)) continue
    const caseId = String(raw.id ?? '')
    for (const key of OUTPUT_KEYS) {
      if (key in raw) {
        outputs.set(caseId, String(raw[key]))
        break
      }
    }
  }
  const missing = suite.cases.filter((testCase) => !outputs.has(testCase.id)).map((c) => c.id)
  if (missing.length > 0) {
    throw new Error(`deterministic eval runner requires outputs for case(s): ${missing.join(', ')}`)
  }
  return outputs
}

function loadBaselineScore(path: string): number {
  const data = loadEvalDocument(path)
  let score = data.score
  if ((score === undefined || score === null) && isRecord(data.report)) score = data.report.score
  if (score === undefined || score === null) throw new Error(`${path}: baseline report missing score`)
  return Number(score)
}

function resolveRelative(baseFile: string, value: string): string {
  const path = expandUser(value)
  return isAbsolute(path) ? path : resolve(dirname(baseFile), path)
}

function expandUser(path: string): string {
  if (path === '~' || path.startsWith('~/')) return resolve(process.env.HOME ?? '', path.slice(2))
  return path
}

function firstTruthy(...values: unknown[]): unknown {
  for (const value of values) {
    if (value === undefined || value === null || value === false || value === '' || value === 0) continue
    if (isRecord(value) && Object.keys(value).length === 0) continue
    if (Array.isArray(value) && value.length === 0) continue
    return value
  }
  return undefined
}

function round8(value: number): number {
  return Math.round(value * 1e8) / 1e8
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function asRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {}
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
