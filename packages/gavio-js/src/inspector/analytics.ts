/**
 * Aggregations over trace summaries — DAG, sessions, stats (F-OBS-10 / F-DX-08).
 *
 * Pure functions over the summary records produced by the {@link TraceBuffer}.
 * The JSON shapes are shared across all three SDK servers, so keep them
 * byte-compatible with the Python reference (`gavio/inspector/analytics.py`).
 */

const GROUP_BY_FIELDS = {
  provider: 'provider',
  model: 'model',
  agent_id: 'agentId',
} as const

type GroupBy = keyof typeof GROUP_BY_FIELDS

/** Token usage as it appears on the wire (camelCase). */
export interface UsageJson {
  promptTokens?: number
  completionTokens?: number
  totalTokens?: number
}

/**
 * Structural view of a trace summary — accepts {@link TraceSummary} records
 * from the live buffer as well as plain objects (tests, seeded stores).
 */
export interface SummaryLike {
  traceId: string
  parentTraceId?: string | null
  agentId?: string | null
  sessionId?: string | null
  provider?: string | null
  model?: string | null
  status?: string | null
  latencyMs?: number | null
  costUsd?: number | null
  cacheHit?: boolean | null
  cacheType?: string | null
  wallTimeUtc?: string | null
  piiEntityTypes?: string[] | null
  interceptorsFired?: string[] | null
  usage?: UsageJson | null
  promptHash?: string | null
  responseHash?: string | null
  driftAlerts?: string[] | null
}

export interface SessionAggregate {
  sessionId: string
  traces: number
  errors: number
  totalCostUsd: number
  totalLatencyMs: number
  agents: string[]
  firstWallTimeUtc: string | null
  lastWallTimeUtc: string | null
}

export interface SubtreeRollup {
  traces: number
  errors: number
  costUsd: number
  latencyMs: number
}

export interface DagNode {
  traceId: string
  parentTraceId: string | null
  agentId: string | null
  sessionId: string | null
  provider: string | null
  model: string | null
  status: string | null
  latencyMs: number | null
  costUsd: number | null
  cacheHit: boolean | null
  wallTimeUtc: string | null
  subtree: SubtreeRollup
}

export interface Dag {
  nodes: DagNode[]
  edges: Array<{ from: string; to: string }>
}

export interface StatsAggregate {
  requests: number
  errors: number
  errorRate: number
  latencyMs: { p50: number | null; p95: number | null; p99: number | null }
  tokens: { prompt: number; completion: number; total: number }
  costUsd: number
  cacheHits: number
  cacheHitRate: number
  piiDetections: Record<string, number>
  driftAlerts: Record<string, number>
}

export interface Stats {
  total: StatsAggregate
  groups?: Record<string, StatsAggregate>
}

function round(value: number, decimals: number): number {
  const factor = 10 ** decimals
  return Math.round(value * factor) / factor
}

function isError(s: SummaryLike): boolean {
  return s.status === 'error' || s.status === 'blocked'
}

/** Group summaries by sessionId — trace counts, cost, duration, agents. */
export function buildSessions(summaries: SummaryLike[]): SessionAggregate[] {
  const sessions = new Map<string, SessionAggregate>()
  for (const s of summaries) {
    const sessionId = s.sessionId
    if (!sessionId) continue
    let entry = sessions.get(sessionId)
    if (entry === undefined) {
      entry = {
        sessionId,
        traces: 0,
        errors: 0,
        totalCostUsd: 0,
        totalLatencyMs: 0,
        agents: [],
        firstWallTimeUtc: s.wallTimeUtc ?? null,
        lastWallTimeUtc: s.wallTimeUtc ?? null,
      }
      sessions.set(sessionId, entry)
    }
    entry.traces += 1
    if (isError(s)) entry.errors += 1
    entry.totalCostUsd = round(entry.totalCostUsd + (s.costUsd ?? 0), 8)
    entry.totalLatencyMs += s.latencyMs ?? 0
    const agent = s.agentId
    if (agent && !entry.agents.includes(agent)) entry.agents.push(agent)
    entry.lastWallTimeUtc = s.wallTimeUtc ?? entry.lastWallTimeUtc
  }
  return [...sessions.values()]
}

/**
 * Agent call graph from parentTraceId links, with subtree rollups.
 *
 * Select nodes by `sessionId` or by `root` trace id (the root plus every
 * descendant). Returns null when `root` is given but unknown.
 */
export function buildDag(
  summaries: SummaryLike[],
  root?: string,
  sessionId?: string,
): Dag | null {
  const byId = new Map<string, SummaryLike>(summaries.map((s) => [s.traceId, s]))
  const children = new Map<string, string[]>()
  for (const s of summaries) {
    const parent = s.parentTraceId
    if (!parent) continue
    const siblings = children.get(parent)
    if (siblings === undefined) children.set(parent, [s.traceId])
    else siblings.push(s.traceId)
  }

  let selected: string[]
  if (sessionId !== undefined) {
    selected = summaries.filter((s) => s.sessionId === sessionId).map((s) => s.traceId)
  } else {
    if (root === undefined || !byId.has(root)) return null
    selected = []
    const stack = [root]
    const seen = new Set<string>()
    while (stack.length > 0) {
      const traceId = stack.pop()!
      if (seen.has(traceId)) continue // defensive: a parentTraceId cycle must not hang us
      seen.add(traceId)
      selected.push(traceId)
      stack.push(...(children.get(traceId) ?? []))
    }
  }

  const nodeSet = new Set(selected)

  const subtree = (traceId: string, seen: Set<string>): SubtreeRollup => {
    seen.add(traceId)
    const s = byId.get(traceId)!
    const rollup: SubtreeRollup = {
      traces: 1,
      errors: isError(s) ? 1 : 0,
      costUsd: s.costUsd ?? 0,
      latencyMs: s.latencyMs ?? 0,
    }
    for (const child of children.get(traceId) ?? []) {
      if (nodeSet.has(child) && !seen.has(child)) {
        const childRollup = subtree(child, seen)
        rollup.traces += childRollup.traces
        rollup.errors += childRollup.errors
        rollup.costUsd += childRollup.costUsd
        rollup.latencyMs += childRollup.latencyMs
      }
    }
    rollup.costUsd = round(rollup.costUsd, 8)
    return rollup
  }

  const nodes: DagNode[] = []
  for (const traceId of selected) {
    const s = byId.get(traceId)
    if (s === undefined) continue
    nodes.push({
      traceId,
      parentTraceId: s.parentTraceId ?? null,
      agentId: s.agentId ?? null,
      sessionId: s.sessionId ?? null,
      provider: s.provider ?? null,
      model: s.model ?? null,
      status: s.status ?? null,
      latencyMs: s.latencyMs ?? null,
      costUsd: s.costUsd ?? null,
      cacheHit: s.cacheHit ?? null,
      wallTimeUtc: s.wallTimeUtc ?? null,
      subtree: subtree(traceId, new Set()),
    })
  }
  const edges: Array<{ from: string; to: string }> = []
  for (const traceId of selected) {
    const s = byId.get(traceId)
    if (s === undefined) continue
    const parent = s.parentTraceId
    if (parent && nodeSet.has(parent)) edges.push({ from: parent, to: s.traceId })
  }
  return { nodes, edges }
}

/**
 * RED aggregates: rate, errors, latency percentiles, tokens, cost, cache, PII.
 *
 * Throws an Error for an unknown `groupBy` or an unparsable `since`.
 */
export function buildStats(
  summaries: SummaryLike[],
  groupBy?: string,
  since?: string,
): Stats {
  if (groupBy !== undefined && !(groupBy in GROUP_BY_FIELDS)) {
    throw new Error("group_by must be one of ['agent_id', 'model', 'provider']")
  }
  if (since !== undefined) {
    const sinceMs = Date.parse(since)
    if (Number.isNaN(sinceMs)) {
      throw new Error(`invalid since timestamp: ${JSON.stringify(since)}`)
    }
    summaries = summaries.filter(
      (s) => s.wallTimeUtc != null && Date.parse(s.wallTimeUtc) >= sinceMs,
    )
  }

  const out: Stats = { total: aggregate(summaries) }
  if (groupBy !== undefined) {
    const field = GROUP_BY_FIELDS[groupBy as GroupBy]
    const groups = new Map<string, SummaryLike[]>()
    for (const s of summaries) {
      const key = String(s[field])
      const members = groups.get(key)
      if (members === undefined) groups.set(key, [s])
      else members.push(s)
    }
    out.groups = {}
    for (const [key, members] of groups) out.groups[key] = aggregate(members)
  }
  return out
}

function aggregate(summaries: SummaryLike[]): StatsAggregate {
  const latencies = summaries
    .map((s) => s.latencyMs)
    .filter((v): v is number => v !== null && v !== undefined)
    .sort((a, b) => a - b)
  const errors = summaries.filter(isError).length
  const cacheHits = summaries.filter((s) => s.cacheHit).length
  let prompt = 0
  let completion = 0
  for (const s of summaries) {
    prompt += s.usage?.promptTokens ?? 0
    completion += s.usage?.completionTokens ?? 0
  }
  const pii: Record<string, number> = {}
  for (const s of summaries) {
    for (const entityType of s.piiEntityTypes ?? []) {
      pii[entityType] = (pii[entityType] ?? 0) + 1
    }
  }
  const drift: Record<string, number> = {}
  for (const s of summaries) {
    for (const metric of s.driftAlerts ?? []) {
      drift[metric] = (drift[metric] ?? 0) + 1
    }
  }
  const n = summaries.length
  return {
    requests: n,
    errors,
    errorRate: n > 0 ? round(errors / n, 4) : 0,
    latencyMs: {
      p50: percentile(latencies, 50),
      p95: percentile(latencies, 95),
      p99: percentile(latencies, 99),
    },
    tokens: { prompt, completion, total: prompt + completion },
    costUsd: round(
      summaries.reduce((sum, s) => sum + (s.costUsd ?? 0), 0),
      8,
    ),
    cacheHits,
    cacheHitRate: n > 0 ? round(cacheHits / n, 4) : 0,
    piiDetections: pii,
    driftAlerts: drift,
  }
}

/** Nearest-rank percentile over an ascending list; null when empty. */
function percentile(sortedValues: number[], pct: number): number | null {
  if (sortedValues.length === 0) return null
  const rank = Math.max(1, Math.ceil((pct / 100) * sortedValues.length))
  return sortedValues[rank - 1]!
}
