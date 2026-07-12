/** Prompt Registry + Evals foundation (F-EVAL-01/F-EVAL-02). */

import { createHash } from 'node:crypto'

import { GavioRequest } from '../request.js'
import { PromptLineage, Provider } from '../types.js'
import type { Message, PromptLineageInit, Provider as ProviderType } from '../types.js'

const PLACEHOLDER = /{{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*}}/g

export interface PromptTemplateInit {
  id: string
  version: string
  messages: Message[]
  requiredVariables?: string[]
  metadata?: Record<string, unknown>
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

  constructor(init: PromptTemplateInit) {
    this.id = init.id
    this.version = init.version
    this.messages = init.messages.map((message) => ({ ...message }))
    this.requiredVariables = [...(init.requiredVariables ?? [])]
    this.metadata = { ...(init.metadata ?? {}) }
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
    return {
      id: this.id,
      version: this.version,
      messages: this.messages.map((message) => ({ ...message })),
      requiredVariables: [...this.requiredVariables],
      metadata: { ...this.metadata },
    }
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
    this.latest.set(parsed.id, parsed.version)
    return parsed
  }

  get(templateId: string, version?: string | null): PromptTemplate {
    const resolved = version ?? this.latest.get(templateId)
    if (resolved === undefined) throw new Error(`prompt template not found: ${templateId}@latest`)
    const template = this.templates.get(key(templateId, resolved))
    if (template === undefined) throw new Error(`prompt template not found: ${templateId}@${resolved}`)
    return template
  }

  render(templateId: string, variables: Record<string, unknown>, version?: string | null): RenderedPrompt {
    return this.get(templateId, version).render(variables)
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
}

export class EvalCase {
  readonly id: string
  readonly templateId: string
  readonly templateVersion: string | null
  readonly variables: Record<string, unknown>
  readonly assertions: EvalAssertion[]
  readonly metadata: Record<string, unknown>

  constructor(init: EvalCaseInit) {
    this.id = init.id
    this.templateId = init.templateId
    this.templateVersion = init.templateVersion ?? null
    this.variables = { ...init.variables }
    this.assertions = init.assertions.map((assertion) => new EvalAssertion(assertion))
    this.metadata = { ...(init.metadata ?? {}) }
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
      cases.push({
        id: testCase.id,
        templateId: testCase.templateId,
        templateVersion: prompt.lineage.templateVersion ?? '',
        passed,
        score,
        outputHash: createHash('sha256').update(output, 'utf8').digest('hex'),
        assertions,
        lineage: prompt.lineage.toJSON(),
      })
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
