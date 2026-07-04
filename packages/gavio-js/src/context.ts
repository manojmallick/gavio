/** Per-request context passed through the interceptor pipeline. */

export interface InterceptorContextInit {
  traceId: string
  agentId?: string | null
  parentTraceId?: string | null
  sessionId?: string | null
  dryRun?: boolean
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
