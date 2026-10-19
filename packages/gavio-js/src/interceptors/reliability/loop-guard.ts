/**
 * Loop guard policy, state, evaluation and interceptor (F-REL-09).
 *
 * A loop guard stops a runaway agent: it keeps a small state per root run (see `RootRunResolver`)
 * and, before every provider call, compares that state with a {@link LoopGuardPolicy}.
 * {@link evaluateLoopGuard} is the pure part — no clock, no I/O, no storage — pinned by the
 * shared vectors in `test-vectors/loop-guard/decisions.json`. Semantics are in `spec/README.md`
 * ('Loop guard'); the shapes are `spec/LoopGuardPolicy.schema.json` and
 * `spec/LoopGuardDecision.schema.json`. Port of
 * `gavio.interceptors.reliability.loop_guard` (Python).
 */

import type { InterceptorContext, LoopGuardAuditEntry } from '../../context.js'
import { ConfigurationError, LoopGuardError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import { DEFAULT_MAX_ENTRIES, RootRunResolver } from '../../root-run.js'
import type { ToolCall } from '../../types.js'
import type { Interceptor } from '../base.js'

/** Limits in the order they are reported: `breached` is the first one reached. */
export const BREACH_ORDER = [
  'max_steps',
  'max_cost',
  'max_wall_time',
  'repeated_tool_call',
] as const
export type LoopGuardBreach = (typeof BREACH_ORDER)[number]

export const LOOP_GUARD_ACTIONS = ['stop', 'fallback', 'dry_run'] as const
export type LoopGuardAction = (typeof LOOP_GUARD_ACTIONS)[number]

export const LOOP_GUARD_NAME = 'loop_guard'
const RUN_KEY = 'loop_guard:run_id'

// ---- policy ---------------------------------------------------------------------------

export interface LoopGuardPolicyInit {
  id: string
  /** What happens on a breach. Default `stop`. */
  action?: LoopGuardAction
  /** Maximum provider calls in one run. */
  maxSteps?: number | null
  /** Maximum recorded spend of one run, in USD. */
  maxCostUsd?: number | null
  /** Maximum seconds from the run's first call to the start of the next one. */
  maxWallTimeSeconds?: number | null
  /** Stop a run that requests the same (tool, arguments) this many times. */
  maxRepeatedToolCalls?: number | null
  /** Required when `action` is `fallback`. */
  fallbackModel?: string | null
  metadata?: Record<string, unknown>
}

/**
 * Limits for one root run. Every limit is optional; `null` is never breached.
 *
 * A limit is what the run may consume: it is checked before a call and reached once it is
 * consumed, so `maxSteps: 25` allows 25 calls and refuses the 26th.
 */
export interface LoopGuardPolicy {
  readonly id: string
  readonly action: LoopGuardAction
  readonly maxSteps: number | null
  readonly maxCostUsd: number | null
  readonly maxWallTimeSeconds: number | null
  readonly maxRepeatedToolCalls: number | null
  readonly fallbackModel: string | null
  readonly metadata: Record<string, unknown>
}

function integerLimit(name: string, value: unknown, minimum: number): number | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    throw new ConfigurationError(`${name} must be an integer`)
  }
  if (value < minimum) throw new ConfigurationError(`${name} must be at least ${minimum}`)
  return value
}

function numberLimit(name: string, value: unknown, minimum: number, exclusive = false): number | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new ConfigurationError(`${name} must be a number`)
  }
  if (value < minimum || (exclusive && value === minimum)) {
    throw new ConfigurationError(
      `${name} must be ${exclusive ? 'greater than' : 'at least'} ${minimum}`,
    )
  }
  return value
}

/** Validate and normalise a policy. An invalid one is a {@link ConfigurationError}. */
export function loopGuardPolicy(init: LoopGuardPolicyInit): LoopGuardPolicy {
  if (typeof init.id !== 'string' || init.id === '') {
    throw new ConfigurationError('loop guard policy id must be a non-empty string')
  }
  const action = init.action ?? 'stop'
  if (!(LOOP_GUARD_ACTIONS as readonly string[]).includes(action)) {
    throw new ConfigurationError(`loop guard action must be one of ${LOOP_GUARD_ACTIONS.join(', ')}`)
  }
  const fallbackModel = init.fallbackModel ?? null
  if (action === 'fallback' && !fallbackModel) {
    throw new ConfigurationError("loop guard action 'fallback' needs a fallbackModel")
  }
  return Object.freeze({
    id: init.id,
    action,
    maxSteps: integerLimit('maxSteps', init.maxSteps, 1),
    maxCostUsd: numberLimit('maxCostUsd', init.maxCostUsd, 0),
    maxWallTimeSeconds: numberLimit('maxWallTimeSeconds', init.maxWallTimeSeconds, 0, true),
    maxRepeatedToolCalls: integerLimit('maxRepeatedToolCalls', init.maxRepeatedToolCalls, 2),
    fallbackModel,
    metadata: { ...(init.metadata ?? {}) },
  })
}

// ---- state ----------------------------------------------------------------------------

/** A tool call the run keeps requesting: its name, arguments hash and request count. */
export interface RepeatedToolCall {
  name: string
  argumentsHash: string
  count: number
}

export interface LoopGuardStateWire {
  steps?: number
  costUsd?: number
  toolCalls?: RepeatedToolCall[]
}

function round8(value: number): number {
  return Math.round(value * 1e8) / 1e8
}

/**
 * What the guard remembers about one root run.
 *
 * `toolCalls` counts requested tool calls by (name, arguments hash) in first-seen order; the
 * hash is `ToolCall.argumentsHash()`, so repeats are found without keeping the arguments.
 * `startedAt` is whatever clock the caller uses; this module never reads one.
 */
export class LoopGuardState {
  steps = 0
  costUsd = 0
  startedAt: number | null = null
  readonly toolCalls = new Map<string, RepeatedToolCall>()

  /**
   * Count a call the guard let through, as soon as it starts. Counting at the start means a
   * loop of calls that fail at the provider still reaches `maxSteps`.
   */
  startCall(): void {
    this.steps += 1
  }

  /** Add what a finished call cost and the tool calls its response asked for. */
  addResult(costUsd: number, toolCalls: Iterable<ToolCall> = []): void {
    this.costUsd = round8(this.costUsd + Math.max(costUsd, 0))
    for (const call of toolCalls) this.countToolCall(call.name, call.argumentsHash(), 1)
  }

  /** Record one call that went through: allowed, fallback or dry_run, never a refused one. */
  record(costUsd: number, toolCalls: Iterable<ToolCall> = []): void {
    this.startCall()
    this.addResult(costUsd, toolCalls)
  }

  private countToolCall(name: string, argumentsHash: string, by: number): void {
    const key = JSON.stringify([name, argumentsHash])
    const existing = this.toolCalls.get(key)
    if (existing) existing.count += by
    else this.toolCalls.set(key, { name, argumentsHash, count: by })
  }

  /** Read the state shape the shared vectors use (`elapsedSeconds` is passed separately). */
  static fromWire(wire: LoopGuardStateWire): LoopGuardState {
    const state = new LoopGuardState()
    state.steps = wire.steps ?? 0
    state.costUsd = wire.costUsd ?? 0
    for (const item of wire.toolCalls ?? []) {
      state.countToolCall(item.name, item.argumentsHash, item.count)
    }
    return state
  }
}

// ---- evaluation -----------------------------------------------------------------------

/** The guard's verdict on one provider call. Metadata only: counts, a cost and a time. */
export interface LoopGuardDecision {
  policyId: string
  runId: string
  /** False only for action `stop`. */
  allowed: boolean
  action: 'allow' | LoopGuardAction
  breached: LoopGuardBreach | null
  breaches: LoopGuardBreach[]
  reason: string
  steps: number
  costUsd: number
  elapsedSeconds: number
  repeatedToolCall: RepeatedToolCall | null
  /** The model to use instead; present only when `action` is `fallback`. */
  targetModel?: string
}

export interface EvaluateLoopGuardOptions {
  policy: LoopGuardPolicy | LoopGuardPolicyInit
  state: LoopGuardState
  runId: string
  elapsedSeconds?: number
}

/**
 * Compare a run's state with a policy before its next provider call.
 *
 * A limit is reached when `steps >= maxSteps`, `cost >= maxCostUsd`,
 * `elapsed >= maxWallTimeSeconds` (cost and elapsed time compared after rounding to 8 places)
 * or any counted tool call has reached `maxRepeatedToolCalls`.
 */
export function evaluateLoopGuard(options: EvaluateLoopGuardOptions): LoopGuardDecision {
  const policy = loopGuardPolicy(options.policy)
  const { state, runId } = options
  const cost = round8(Math.max(state.costUsd, 0))
  const elapsed = round8(Math.max(options.elapsedSeconds ?? 0, 0))

  const breaches: LoopGuardBreach[] = []
  let repeated: RepeatedToolCall | null = null
  if (policy.maxSteps !== null && state.steps >= policy.maxSteps) breaches.push('max_steps')
  if (policy.maxCostUsd !== null && cost >= policy.maxCostUsd) breaches.push('max_cost')
  if (policy.maxWallTimeSeconds !== null && elapsed >= policy.maxWallTimeSeconds) {
    breaches.push('max_wall_time')
  }
  if (policy.maxRepeatedToolCalls !== null) {
    for (const entry of state.toolCalls.values()) {
      if (entry.count >= policy.maxRepeatedToolCalls) {
        repeated = { ...entry }
        breaches.push('repeated_tool_call')
        break
      }
    }
  }

  const breached = breaches[0] ?? null
  const action: LoopGuardDecision['action'] = breached === null ? 'allow' : policy.action
  const decision: LoopGuardDecision = {
    policyId: policy.id,
    runId,
    allowed: action !== 'stop',
    action,
    breached,
    breaches,
    reason: breached === null ? 'within_limits' : `${breached}_reached`,
    steps: state.steps,
    costUsd: cost,
    elapsedSeconds: elapsed,
    repeatedToolCall: repeated,
  }
  if (action === 'fallback' && policy.fallbackModel) decision.targetModel = policy.fallbackModel
  return decision
}

// ---- store ----------------------------------------------------------------------------

export interface LoopGuardStoreOptions {
  /** Most runs kept; the least recently used is evicted first. Default 10,000. */
  maxRuns?: number
  /** Seconds a run may idle before it is forgotten. Default 3600. */
  ttlSeconds?: number
  /** Seconds from any monotonic clock. Default `performance.now() / 1000`. */
  clock?: () => number
}

const defaultClock = (): number => performance.now() / 1000

/**
 * Bounded, idle-expiring in-memory store of per-run state.
 *
 * Keeps at most `maxRuns` runs (least recently used evicted first) and forgets a run that has
 * been idle for `ttlSeconds`, so a long-lived process cannot grow without bound. A run that
 * expires starts again from zero if it comes back.
 */
export class LoopGuardStore {
  private readonly runs = new Map<string, { state: LoopGuardState; lastSeen: number }>()
  private readonly maxRuns: number
  private readonly ttl: number
  private readonly clock: () => number

  constructor(options: LoopGuardStoreOptions = {}) {
    const maxRuns = options.maxRuns ?? DEFAULT_MAX_ENTRIES
    const ttl = options.ttlSeconds ?? 3600
    if (!Number.isInteger(maxRuns) || maxRuns < 1) {
      throw new ConfigurationError('maxRuns must be an integer of at least 1')
    }
    if (!(ttl > 0)) throw new ConfigurationError('ttlSeconds must be positive')
    this.maxRuns = maxRuns
    this.ttl = ttl
    this.clock = options.clock ?? defaultClock
  }

  /** The state of `runId`, created (and started now) if it is new or has expired. */
  acquire(runId: string): LoopGuardState {
    const now = this.clock()
    this.expire(now)
    const existing = this.runs.get(runId)
    let state = existing?.state
    if (!state) {
      state = new LoopGuardState()
      state.startedAt = now
    }
    this.runs.delete(runId)
    this.runs.set(runId, { state, lastSeen: now })
    while (this.runs.size > this.maxRuns) {
      this.runs.delete(this.runs.keys().next().value as string)
    }
    return state
  }

  /** The state of `runId` if it is still remembered; refreshes it. */
  get(runId: string): LoopGuardState | undefined {
    const now = this.clock()
    this.expire(now)
    const entry = this.runs.get(runId)
    if (!entry) return undefined
    this.runs.delete(runId)
    this.runs.set(runId, { state: entry.state, lastSeen: now })
    return entry.state
  }

  get size(): number {
    return this.runs.size
  }

  private expire(now: number): void {
    // Entries are ordered by recency, so the idle ones are all at the front.
    for (const [runId, entry] of this.runs) {
      if (now - entry.lastSeen < this.ttl) break
      this.runs.delete(runId)
    }
  }
}

// ---- interceptor ----------------------------------------------------------------------

export interface LoopGuardOptions {
  policy: LoopGuardPolicy | LoopGuardPolicyInit
  resolver?: RootRunResolver
  store?: LoopGuardStore
  /** Seconds from any monotonic clock. Default `performance.now() / 1000`. */
  clock?: () => number
}

/**
 * Stop a runaway agent loop (F-REL-09).
 *
 * Before every provider call the guard resolves the request's root run (`metadata.runId`, else
 * the root of the `parentTraceId` chain, else the trace id), compares the run's state with the
 * policy and acts on the verdict: `stop` throws a {@link LoopGuardError}, `fallback` reroutes
 * the call to `fallbackModel`, `dry_run` only records the breach. After the call it adds the
 * response's cost and tool calls to the run. The verdict is attached to the audit record
 * (counts only), the Inspector, and, on a breach, a governance event.
 *
 * An agent loop must pass `metadata.runId` or `parentTraceId` on its calls — calls with neither
 * are separate runs, which no limit can link. In a gateway `dryRun` the guard still evaluates
 * and records, but never stops or reroutes a call. State is in memory, per guard.
 */
export function loopGuard(options: LoopGuardOptions): Interceptor {
  const policy = loopGuardPolicy(options.policy)
  const clock = options.clock ?? defaultClock
  const resolver = options.resolver ?? new RootRunResolver()
  const store = options.store ?? new LoopGuardStore({ clock })

  return {
    name: LOOP_GUARD_NAME,

    before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
      ctx.markFired(LOOP_GUARD_NAME)
      const runId = resolver.resolve(request.traceId, request.parentTraceId, request.metadata)
      const state = store.acquire(runId)
      const now = clock()
      let decision = evaluateLoopGuard({
        policy,
        state,
        runId,
        elapsedSeconds: now - (state.startedAt ?? now),
      })
      if (ctx.dryRun && (decision.action === 'stop' || decision.action === 'fallback')) {
        // A gateway dry run observes; it never refuses or reroutes a call.
        const { targetModel: _dropped, ...rest } = decision
        decision = { ...rest, action: 'dry_run', allowed: true }
      }
      if (decision.allowed) state.startCall()

      const entry: LoopGuardAuditEntry = {
        policyId: decision.policyId,
        runId: decision.runId,
        action: decision.action,
        breached: decision.breached,
        steps: decision.steps,
        costUsd: decision.costUsd,
        elapsedSeconds: decision.elapsedSeconds,
      }
      ctx.loopGuard = entry
      ctx.inspect('loop_guard', decision)
      if (decision.breached !== null) {
        ctx.recordGovernanceEvent({ kind: 'loop_guard', decision, policyId: policy.id })
      }
      if (decision.action === 'stop') {
        throw new LoopGuardError(stopMessage(decision), { ...decision })
      }
      ctx.state[RUN_KEY] = runId
      if (
        decision.action === 'fallback' &&
        decision.targetModel !== undefined &&
        request.model !== decision.targetModel
      ) {
        const rerouted = request.copyWithMessages(request.messages)
        rerouted.model = decision.targetModel
        return rerouted
      }
      return request
    },

    after(response: GavioResponse, ctx: InterceptorContext): GavioResponse {
      const runId = ctx.state[RUN_KEY]
      if (typeof runId === 'string') {
        // undefined: the run expired or was evicted while the call was in flight
        store.get(runId)?.addResult(response.costUsd, response.toolCalls)
      }
      return response
    },
  }
}

function stopMessage(decision: LoopGuardDecision): string {
  let detail = ''
  if (decision.repeatedToolCall !== null) {
    const call = decision.repeatedToolCall
    detail = ` ('${call.name}' requested ${call.count} times with the same arguments)`
  }
  return (
    `loop guard ${decision.policyId} stopped run ${decision.runId}: ` +
    `${decision.breached} reached${detail} after ${decision.steps} steps, ` +
    `$${decision.costUsd.toFixed(4)}, ${decision.elapsedSeconds.toFixed(1)}s`
  )
}
