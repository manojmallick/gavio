/** Tool Runtime (F-TOOL-01/02/03/04). */

import type { InterceptorContext } from '../../context.js'
import { ToolRuntimeError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { Interceptor } from '../base.js'

export interface ToolRuntimeOptions {
  onFailure?: 'warn' | 'error'
  maxAgeSeconds?: number
  conflictKeys?: string[]
  now?: Date
}

export interface ToolRuntimeDecision {
  callCount: number
  violations: Record<string, unknown>[]
  conflicts: Record<string, unknown>[]
  confidence: number
  provenance: Record<string, unknown>[]
}

export function toolRuntime(options: ToolRuntimeOptions = {}): Interceptor {
  const onFailure = options.onFailure ?? 'warn'
  if (onFailure !== 'warn' && onFailure !== 'error') {
    throw new Error("onFailure must be 'warn' or 'error'")
  }
  return {
    name: 'tool_runtime',
    before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
      ctx.markFired('tool_runtime')
      const decision = analyzeToolRuntime(ctx.tools, options)
      ctx.tools['runtime'] = decision
      ctx.inspect('tool_runtime', decision)
      for (const conflict of decision.conflicts) {
        ctx.recordGovernanceEvent({ kind: 'tool_conflict', ...conflict })
      }
      if (decision.violations.length > 0 && onFailure === 'error' && !ctx.dryRun) {
        throw new ToolRuntimeError(
          decision.violations.map((v) => String(v['message'])).join('; '),
        )
      }
      return request
    },
  }
}

export function analyzeToolRuntime(
  tools: Record<string, unknown> | undefined,
  options: ToolRuntimeOptions = {},
): ToolRuntimeDecision {
  const context = { ...(tools ?? {}) }
  const calls = toolCalls(context)
  const referenceTime = options.now ?? parseTime(first(context, 'now', 'evaluated_at')) ?? new Date()
  const defaultMaxAge =
    options.maxAgeSeconds ?? numberValue(first(context, 'max_age_seconds', 'maxAgeSeconds'))

  const violations: Record<string, unknown>[] = []
  const provenance: Record<string, unknown>[] = []
  const confidenceValues: number[] = []

  for (const call of calls) {
    const toolId = toolIdOf(call)
    const toolName = toolNameOf(call)
    const result = record(first(call, 'result', 'output'))
    const input = record(first(call, 'input', 'arguments', 'args'))

    for (const [label, value, schema] of [
      ['input', input, record(first(call, 'input_schema', 'inputSchema'))],
      ['output', result, record(first(call, 'output_schema', 'outputSchema', 'schema'))],
    ] as const) {
      if (Object.keys(schema).length > 0) {
        violations.push(...validateSchema(value, schema, label, toolId, toolName))
      }
    }

    const createdAt = parseTime(first(call, 'created_at', 'createdAt', 'timestamp', 'observed_at'))
    const ttl =
      numberValue(first(call, 'ttl_seconds', 'ttlSeconds', 'max_age_seconds', 'maxAgeSeconds')) ??
      defaultMaxAge
    if (createdAt !== null && ttl !== undefined) {
      const ageSeconds = Math.max(0, (referenceTime.getTime() - createdAt.getTime()) / 1000)
      if (ageSeconds > ttl) {
        violations.push(
          violation(
            'freshness',
            toolId,
            toolName,
            `tool result is stale: age ${ageSeconds.toFixed(1)}s exceeds ${ttl.toFixed(1)}s`,
            { age_seconds: round(ageSeconds), max_age_seconds: ttl },
          ),
        )
      }
    }

    const confidence = numberValue(call['confidence'])
    if (confidence !== undefined) confidenceValues.push(confidence)
    provenance.push({
      tool_id: toolId,
      tool_name: toolName,
      source: String(first(call, 'source', 'provider', 'provenance') ?? 'unknown'),
      created_at: createdAt ? createdAt.toISOString().replace('.000Z', 'Z') : null,
      cache_hit: Boolean(first(call, 'cache_hit', 'cacheHit') ?? false),
      confidence,
      result_keys: Object.keys(result).sort(),
    })
  }

  const conflicts = conflictRecords(calls, context, options.conflictKeys ?? [])
  return {
    callCount: calls.length,
    violations,
    conflicts,
    confidence: overallConfidence(conflicts, confidenceValues),
    provenance,
  }
}

function toolCalls(tools: Record<string, unknown>): Record<string, unknown>[] {
  const raw = first(tools, 'calls', 'tool_calls', 'toolCalls', 'results')
  if (!Array.isArray(raw)) return []
  return raw.filter(isRecord).map((item) => ({ ...item }))
}

function conflictRecords(
  calls: Record<string, unknown>[],
  tools: Record<string, unknown>,
  conflictKeys: string[],
): Record<string, unknown>[] {
  const configured = first(tools, 'conflict_keys', 'conflictKeys')
  const keys = new Set(conflictKeys)
  if (Array.isArray(configured)) for (const key of configured) keys.add(String(key))
  return Array.from(keys)
    .sort()
    .flatMap((key) => {
      const buckets = new Map<string, string[]>()
      for (const call of calls) {
        const result = record(first(call, 'result', 'output'))
        if (result[key] === undefined) continue
        const value = stableValue(result[key])
        buckets.set(value, [...(buckets.get(value) ?? []), toolIdOf(call)])
      }
      if (buckets.size <= 1) return []
      const total = Array.from(buckets.values()).reduce((sum, ids) => sum + ids.length, 0)
      const largest = Math.max(...Array.from(buckets.values()).map((ids) => ids.length))
      return [
        {
          key,
          values: Array.from(buckets.keys()).sort(),
          tool_ids: Array.from(buckets.values()).flat().sort(),
          confidence: round(largest / total),
        },
      ]
    })
}

function validateSchema(
  value: Record<string, unknown>,
  schema: Record<string, unknown>,
  label: string,
  toolId: string,
  toolName: string,
): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = []
  const required = schema['required']
  if (Array.isArray(required)) {
    for (const field of required) {
      const key = String(field)
      if (value[key] === undefined) {
        out.push(violation('schema', toolId, toolName, `${label} missing required field ${key}`))
      }
    }
  }
  const properties = schema['properties']
  if (isRecord(properties)) {
    for (const [key, spec] of Object.entries(properties)) {
      if (value[key] !== undefined && !matchesType(value[key], spec)) {
        out.push(violation('schema', toolId, toolName, `${label}.${key} has invalid type`))
      }
    }
  }
  return out
}

function matchesType(value: unknown, spec: unknown): boolean {
  const expected = isRecord(spec) ? spec['type'] : spec
  if (Array.isArray(expected)) return expected.some((item) => matchesType(value, item))
  switch (String(expected)) {
    case 'string':
      return typeof value === 'string'
    case 'number':
      return typeof value === 'number' && Number.isFinite(value)
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value)
    case 'boolean':
      return typeof value === 'boolean'
    case 'object':
      return isRecord(value)
    case 'array':
      return Array.isArray(value)
    case 'null':
      return value === null
    default:
      return true
  }
}

function violation(
  kind: string,
  toolId: string,
  toolName: string,
  message: string,
  extra: Record<string, unknown> = {},
): Record<string, unknown> {
  return { kind, tool_id: toolId, tool_name: toolName, message, ...extra }
}

function overallConfidence(conflicts: Record<string, unknown>[], values: number[]): number {
  if (conflicts.length > 0) {
    return Math.min(...conflicts.map((conflict) => Number(conflict['confidence'])))
  }
  if (values.length > 0) return round(values.reduce((sum, value) => sum + value, 0) / values.length)
  return 1
}

function toolIdOf(call: Record<string, unknown>): string {
  return String(first(call, 'id', 'tool_call_id', 'toolCallId') ?? toolNameOf(call))
}

function toolNameOf(call: Record<string, unknown>): string {
  return String(first(call, 'name', 'tool', 'tool_name', 'toolName') ?? 'tool')
}

function first(source: Record<string, unknown>, ...keys: string[]): unknown {
  for (const key of keys) if (source[key] !== undefined) return source[key]
  return undefined
}

function record(value: unknown): Record<string, unknown> {
  return isRecord(value) ? { ...value } : {}
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function parseTime(value: unknown): Date | null {
  if (typeof value !== 'string') return null
  const millis = Date.parse(value)
  return Number.isNaN(millis) ? null : new Date(millis)
}

function stableValue(value: unknown): string {
  if (value === null || ['string', 'number', 'boolean'].includes(typeof value)) return String(value)
  return JSON.stringify(stableNormalize(value))
}

function stableNormalize(value: unknown): unknown {
  if (Array.isArray(value)) return value.map((item) => stableNormalize(item))
  if (!isRecord(value)) return value
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((key) => [key, stableNormalize(value[key])]),
  )
}

function round(value: number): number {
  return Math.round(value * 10000) / 10000
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
