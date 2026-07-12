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
  decisions: Record<string, unknown>[]
  approvalsRequired: number
  blocked: number
  replayable: boolean
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
  const definitions = toolDefinitions(context)
  const referenceTime = options.now ?? parseTime(first(context, 'now', 'evaluated_at')) ?? new Date()
  const defaultMaxAge =
    options.maxAgeSeconds ?? numberValue(first(context, 'max_age_seconds', 'maxAgeSeconds'))
  const grantedPermissions = permissionsGranted(context)
  const approvals = approvalRecords(context)

  const violations: Record<string, unknown>[] = []
  const provenance: Record<string, unknown>[] = []
  const confidenceValues: number[] = []
  const decisions: Record<string, unknown>[] = []

  for (const call of calls) {
    const toolId = toolIdOf(call)
    const toolName = toolNameOf(call)
    const definition = toolDefinition(call, definitions)
    const result = record(first(call, 'result', 'output'))
    const input = record(first(call, 'input', 'arguments', 'args'))
    const requiredPermissions = permissionsRequired(call, definition)
    const missingPermissions = requiredPermissions.filter(
      (permission) => !permissionGranted(permission, grantedPermissions),
    )
    const risk = riskLevel(call, definition)
    const needsApproval = approvalRequired(call, definition, risk, requiredPermissions)
    const approval = matchingApproval(call, approvals, referenceTime)
    const approved = approval !== null

    for (const [label, value, schema] of [
      [
        'input',
        input,
        firstNonEmpty(
          record(first(call, 'input_schema', 'inputSchema')),
          record(first(definition, 'input_schema', 'inputSchema')),
        ),
      ],
      [
        'output',
        result,
        firstNonEmpty(
          record(first(call, 'output_schema', 'outputSchema', 'schema')),
          record(first(definition, 'output_schema', 'outputSchema', 'schema')),
        ),
      ],
    ] as const) {
      if (Object.keys(schema).length > 0) {
        violations.push(...validateSchema(value, schema, label, toolId, toolName))
      }
    }

    const createdAt = parseTime(first(call, 'created_at', 'createdAt', 'timestamp', 'observed_at'))
    const ttl =
      numberValue(first(call, 'ttl_seconds', 'ttlSeconds', 'max_age_seconds', 'maxAgeSeconds')) ??
      numberValue(first(definition, 'freshness_ttl_seconds', 'freshnessTtlSeconds')) ??
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

    const mcp = mcpMetadata(call, definition)
    let source = first(call, 'source', 'provider', 'provenance')
    if (source === undefined && Object.keys(mcp).length > 0) {
      source = mcp['server'] ?? mcp['tool'] ?? 'mcp'
    }
    if (
      boolValue(first(call, 'provenance_required', 'provenanceRequired')) ||
      boolValue(first(definition, 'provenance_required', 'provenanceRequired'))
    ) {
      if (source === undefined && Object.keys(mcp).length === 0) {
        violations.push(
          violation('provenance', toolId, toolName, 'tool result is missing required provenance'),
        )
      }
    }

    let action = 'allow'
    if (missingPermissions.length > 0) {
      action = needsApproval && !approved ? 'require_approval' : 'block'
      if (!approved) {
        violations.push(
          violation(
            'permission',
            toolId,
            toolName,
            `tool call is missing required permission(s): ${missingPermissions.join(', ')}`,
            { action, missing_permissions: missingPermissions },
          ),
        )
      }
    }
    if (needsApproval && !approved) {
      action = 'require_approval'
      violations.push(
        violation('approval', toolId, toolName, 'tool call requires approval', { action, risk }),
      )
    }

    const confidence = numberValue(call['confidence'])
    if (confidence !== undefined) confidenceValues.push(confidence)
    provenance.push({
      tool_id: toolId,
      tool_name: toolName,
      source: String(source ?? 'unknown'),
      created_at: createdAt ? createdAt.toISOString().replace('.000Z', 'Z') : null,
      cache_hit: Boolean(first(call, 'cache_hit', 'cacheHit') ?? false),
      confidence,
      mcp: Object.keys(mcp).length > 0 ? mcp : null,
      mcp_server: mcp['server'] ?? null,
      mcp_tool: mcp['tool'] ?? null,
      result_keys: Object.keys(result).sort(),
    })
    decisions.push({
      tool_id: toolId,
      tool_name: toolName,
      action,
      risk,
      permissions_required: requiredPermissions,
      permissions_granted: grantedPermissions,
      missing_permissions: missingPermissions,
      approval_required: needsApproval,
      approved,
      mcp: Object.keys(mcp).length > 0 ? mcp : null,
    })
  }

  const conflicts = conflictRecords(calls, context, options.conflictKeys ?? [])
  return {
    callCount: calls.length,
    violations,
    conflicts,
    confidence: overallConfidence(conflicts, confidenceValues),
    provenance,
    decisions,
    approvalsRequired: decisions.filter((decision) => decision['approval_required'] === true).length,
    blocked: decisions.filter((decision) => decision['action'] === 'block').length,
    replayable: calls.length > 0,
  }
}

export function replayToolRuntime(
  record: Record<string, unknown> | undefined,
  options: ToolRuntimeOptions = {},
): ToolRuntimeDecision {
  return analyzeToolRuntime(record, options)
}

function toolCalls(tools: Record<string, unknown>): Record<string, unknown>[] {
  const raw = first(tools, 'calls', 'tool_calls', 'toolCalls', 'results', 'records')
  if (!Array.isArray(raw)) return []
  return raw.filter(isRecord).map((item) => ({ ...item }))
}

function toolDefinitions(tools: Record<string, unknown>): Map<string, Record<string, unknown>> {
  let raw = first(tools, 'definitions', 'tool_definitions', 'toolDefinitions', 'registry')
  if (isRecord(raw)) raw = first(raw, 'tools', 'definitions') ?? raw
  const definitions: Record<string, unknown>[] = []
  if (Array.isArray(raw)) {
    definitions.push(...raw.filter(isRecord).map((item) => ({ ...item })))
  } else if (isRecord(raw)) {
    for (const [key, value] of Object.entries(raw)) {
      if (!isRecord(value)) continue
      definitions.push({ name: key, ...value })
    }
  }
  const out = new Map<string, Record<string, unknown>>()
  for (const definition of definitions) {
    for (const key of [
      first(definition, 'id', 'tool_id', 'toolId'),
      first(definition, 'name', 'tool', 'tool_name', 'toolName'),
    ]) {
      if (key !== undefined) out.set(String(key), definition)
    }
  }
  return out
}

function toolDefinition(
  call: Record<string, unknown>,
  definitions: Map<string, Record<string, unknown>>,
): Record<string, unknown> {
  const inline = record(first(call, 'definition'))
  if (Object.keys(inline).length > 0) return inline
  for (const key of [
    first(call, 'definition_id', 'definitionId'),
    first(call, 'name', 'tool', 'tool_name', 'toolName'),
    first(call, 'id', 'tool_call_id', 'toolCallId'),
  ]) {
    if (key !== undefined && definitions.has(String(key))) return definitions.get(String(key))!
  }
  return {}
}

function permissionsRequired(
  call: Record<string, unknown>,
  definition: Record<string, unknown>,
): string[] {
  const permissions = new Set<string>()
  for (const source of [definition, call]) {
    for (const key of ['permissions', 'required_permissions', 'requiredPermissions']) {
      const value = source[key]
      if (Array.isArray(value)) for (const item of value) permissions.add(String(item))
    }
  }
  return Array.from(permissions).sort()
}

function permissionsGranted(tools: Record<string, unknown>): string[] {
  const permissions = new Set<string>()
  for (const key of [
    'permissions',
    'granted_permissions',
    'grantedPermissions',
    'allowed_permissions',
    'allowedPermissions',
    'scopes',
  ]) {
    const value = tools[key]
    if (Array.isArray(value)) for (const item of value) permissions.add(String(item))
  }
  return Array.from(permissions).sort()
}

function permissionGranted(required: string, grants: string[]): boolean {
  return grants.some((grant) => {
    if (grant === '*' || grant === required) return true
    return grant.endsWith('.*') && required.startsWith(grant.slice(0, -1))
  })
}

function riskLevel(call: Record<string, unknown>, definition: Record<string, unknown>): string {
  return String(
    first(call, 'risk', 'risk_level', 'riskLevel') ??
      first(definition, 'risk', 'risk_level', 'riskLevel') ??
      'low',
  ).toLowerCase()
}

function approvalRequired(
  call: Record<string, unknown>,
  definition: Record<string, unknown>,
  risk: string,
  permissions: string[],
): boolean {
  const explicit =
    first(call, 'requires_approval', 'requiresApproval') ??
    first(definition, 'requires_approval', 'requiresApproval')
  if (explicit !== undefined) return boolValue(explicit)
  if (['high', 'critical', 'destructive'].includes(risk)) return true
  return permissions.some(
    (permission) =>
      permission.startsWith('destructive.') ||
      permission.startsWith('external_side_effect.') ||
      ['destructive', 'destructive.*', 'external_side_effect', 'external_side_effect.*'].includes(
        permission,
      ),
  )
}

function approvalRecords(tools: Record<string, unknown>): Record<string, unknown>[] {
  const raw = first(tools, 'approvals', 'approval_records', 'approvalRecords')
  return Array.isArray(raw) ? raw.filter(isRecord).map((item) => ({ ...item })) : []
}

function matchingApproval(
  call: Record<string, unknown>,
  approvals: Record<string, unknown>[],
  now: Date,
): Record<string, unknown> | null {
  const inline = record(first(call, 'approval'))
  const candidates = Object.keys(inline).length > 0 ? [inline, ...approvals] : approvals
  const ids = new Set([toolIdOf(call), toolNameOf(call)])
  for (const approval of candidates) {
    const target = first(
      approval,
      'tool_call_id',
      'toolCallId',
      'call_id',
      'callId',
      'tool_id',
      'toolId',
    )
    if (target !== undefined && !ids.has(String(target))) continue
    if (target === undefined) {
      const name = first(approval, 'tool_name', 'toolName', 'name', 'tool')
      if (name !== undefined && !ids.has(String(name))) continue
    }
    const status = String(first(approval, 'status', 'decision') ?? '').toLowerCase()
    const approved =
      boolValue(first(approval, 'approved')) || ['approved', 'allow', 'allowed'].includes(status)
    if (!approved || boolValue(first(approval, 'revoked', 'denied'))) continue
    const expiresAt = parseTime(first(approval, 'expires_at', 'expiresAt'))
    if (expiresAt !== null && expiresAt.getTime() < now.getTime()) continue
    return approval
  }
  return null
}

function mcpMetadata(
  call: Record<string, unknown>,
  definition: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {
    ...record(first(definition, 'mcp', 'mcp_metadata', 'mcpMetadata')),
    ...record(first(call, 'mcp', 'mcp_metadata', 'mcpMetadata')),
  }
  for (const [canonical, keys] of Object.entries({
    server: ['mcp_server', 'mcpServer', 'server'],
    tool: ['mcp_tool', 'mcpTool'],
    session_id: ['mcp_session_id', 'mcpSessionId'],
  })) {
    const value = first(call, ...keys) ?? first(definition, ...keys)
    if (value !== undefined) out[canonical] = String(value)
  }
  return out
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

function firstNonEmpty(
  firstRecord: Record<string, unknown>,
  secondRecord: Record<string, unknown>,
): Record<string, unknown> {
  return Object.keys(firstRecord).length > 0 ? firstRecord : secondRecord
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

function boolValue(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'string') {
    return ['1', 'true', 'yes', 'approved', 'allow', 'allowed'].includes(value.toLowerCase())
  }
  return false
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
