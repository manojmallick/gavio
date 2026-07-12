/** Per-request context passed through the interceptor pipeline. */

import type { GavioRequest } from './request.js'

const COST_DIMENSION_KEYS = [
  'tenant',
  'feature',
  'user',
  'endpoint',
  'environment',
  'workflow',
  'tool',
] as const

export interface InterceptorContextInit {
  traceId: string
  agentId?: string | null
  parentTraceId?: string | null
  sessionId?: string | null
  dryRun?: boolean
  tenant?: string | null
  feature?: string | null
  cost?: Record<string, unknown>
  retry?: Record<string, unknown>
  tools?: Record<string, unknown>
  policy?: Record<string, unknown>
}

/**
 * Mutable scratch space shared by all interceptors within one request. One
 * instance per request — never shared across requests. Interceptors stash
 * signals here (PII findings, cache decisions, risk scores) for the audit
 * interceptor to collect at the end of the chain.
 */
export class InterceptorContext {
  traceId: string
  agentId: string | null
  parentTraceId: string | null
  sessionId: string | null
  dryRun: boolean

  tenant: string | null
  feature: string | null
  cost: Record<string, unknown>
  retry: Record<string, unknown>
  tools: Record<string, unknown>
  policy: Record<string, unknown>

  interceptorsFired: string[] = []
  piiEntityTypes: string[] = []
  piiEntityCounts: Record<string, number> = {}
  cacheHit = false
  cacheType: string | null = null
  riskScore: number | null = null
  guardrailOutcome: string | null = null

  /** Arbitrary inter-interceptor state (e.g. PII replacement map for restore). */
  state: Record<string, unknown> = {}

  /** Pending inspector decision entries; drained per hook by the emitter. */
  private inspectEntries: Record<string, unknown> = {}

  /** Pending governance events (e.g. drift alerts); drained per hook by the chain. */
  private governanceEvents: Record<string, unknown>[] = []

  constructor(init: InterceptorContextInit) {
    this.traceId = init.traceId
    this.agentId = init.agentId ?? null
    this.parentTraceId = init.parentTraceId ?? null
    this.sessionId = init.sessionId ?? null
    this.dryRun = init.dryRun ?? false
    this.tenant = init.tenant ?? null
    this.feature = init.feature ?? null
    this.cost = init.cost ?? {}
    this.retry = init.retry ?? {}
    this.tools = init.tools ?? {}
    this.policy = init.policy ?? {}
  }

  /** Create a context from a request, including first-class runtime metadata. */
  static fromRequest(request: GavioRequest, dryRun = false): InterceptorContext {
    return new InterceptorContext({
      traceId: request.traceId,
      agentId: request.agentId,
      parentTraceId: request.parentTraceId,
      sessionId: request.sessionId,
      dryRun,
      ...runtimeFields(request.metadata),
    })
  }

  /**
   * Attach a decision record for the inspector (F-DX-09). Entries recorded
   * during a hook surface on that hook's `interceptor.*.end` event as `data.
   * decision`. Harmless no-op accumulation when the inspector is disabled.
   */
  inspect(key: string, value: unknown): void {
    this.inspectEntries[key] = value
  }

  /** @internal Drain pending {@link inspect} entries (called per hook). */
  drainInspectEntries(): Record<string, unknown> | undefined {
    if (Object.keys(this.inspectEntries).length === 0) return undefined
    const entries = this.inspectEntries
    this.inspectEntries = {}
    return entries
  }

  /**
   * Record a governance event (e.g. a drift alert) to surface on the inspector
   * as a standalone `governance.event`. The chain drains these after each hook.
   */
  recordGovernanceEvent(data: Record<string, unknown>): void {
    this.governanceEvents.push(data)
  }

  /** @internal Drain pending {@link recordGovernanceEvent} entries (per hook). */
  drainGovernanceEvents(): Record<string, unknown>[] {
    if (this.governanceEvents.length === 0) return []
    const events = this.governanceEvents
    this.governanceEvents = []
    return events
  }

  markFired(name: string): void {
    if (!this.interceptorsFired.includes(name)) {
      this.interceptorsFired.push(name)
    }
  }

  recordPii(entityTypes: string[]): void {
    for (const et of entityTypes) {
      this.piiEntityCounts[et] = (this.piiEntityCounts[et] ?? 0) + 1
      if (!this.piiEntityTypes.includes(et)) {
        this.piiEntityTypes.push(et)
      }
    }
  }
}

function runtimeFields(metadata: Record<string, unknown>): {
  tenant: string | null
  feature: string | null
  cost: Record<string, unknown>
  retry: Record<string, unknown>
  tools: Record<string, unknown>
  policy: Record<string, unknown>
} {
  const cost = section(metadata, ['cost', 'costContext', 'cost_context'])
  const dims = dimensions(metadata, cost)

  const tenant =
    scalar(metadata, ['tenant', 'tenantId', 'tenant_id']) ??
    scalar(cost, ['tenant', 'tenantId', 'tenant_id']) ??
    scalar(dims, ['tenant', 'tenantId', 'tenant_id'])
  const feature =
    scalar(metadata, ['feature', 'featureId', 'feature_id']) ??
    scalar(cost, ['feature', 'featureId', 'feature_id']) ??
    scalar(dims, ['feature', 'featureId', 'feature_id'])

  if (Object.keys(dims).length > 0) cost['dimensions'] = dims
  if (tenant !== null && cost['tenant'] === undefined) cost['tenant'] = tenant
  if (feature !== null && cost['feature'] === undefined) cost['feature'] = feature

  return {
    tenant,
    feature,
    cost,
    retry: section(metadata, ['retry', 'retryContext', 'retry_context']),
    tools: section(metadata, ['tools', 'toolContext', 'tool_context']),
    policy: section(metadata, ['policy', 'policyContext', 'policy_context']),
  }
}

function section(metadata: Record<string, unknown>, keys: string[]): Record<string, unknown> {
  for (const key of keys) {
    const value = metadata[key]
    if (isRecord(value)) return { ...value }
  }
  return {}
}

function dimensions(
  metadata: Record<string, unknown>,
  cost: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  const existing = cost['dimensions']
  if (isRecord(existing)) Object.assign(out, existing)
  for (const key of COST_DIMENSION_KEYS) {
    if (metadata[key] !== undefined) out[key] = metadata[key]
  }
  for (const key of ['costDimensions', 'cost_dimensions']) {
    const value = metadata[key]
    if (isRecord(value)) Object.assign(out, value)
  }
  return out
}

function scalar(metadata: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) {
    const value = metadata[key]
    if (value !== undefined && value !== null && typeof value !== 'object') {
      return String(value)
    }
  }
  return null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
