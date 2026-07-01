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

  constructor(init: InterceptorContextInit) {
    this.traceId = init.traceId
    this.agentId = init.agentId ?? null
    this.parentTraceId = init.parentTraceId ?? null
    this.sessionId = init.sessionId ?? null
    this.dryRun = init.dryRun ?? false
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
