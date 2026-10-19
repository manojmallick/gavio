/**
 * Loop guard (F-REL-09): shared vectors, the interceptor, its store, and the audit / inspector
 * wiring.
 *
 * Runs the same `test-vectors/loop-guard/decisions.json` the Python SDK runs. The limit
 * semantics are pinned by the vectors; the interceptor tests cover the wiring around them.
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { InterceptorContext } from '../../src/context.js'
import { ConfigurationError, LoopGuardError, ProviderUnavailableError } from '../../src/errors.js'
import { Gateway } from '../../src/gateway.js'
import { auditInterceptor, verifyChain } from '../../src/interceptors/audit/index.js'
import { AuditRecord } from '../../src/interceptors/audit/record.js'
import type { AuditSink } from '../../src/interceptors/audit/sink.js'
import {
  BREACH_ORDER,
  LoopGuardState,
  LoopGuardStore,
  evaluateLoopGuard,
  loopGuard,
  loopGuardPolicy,
} from '../../src/interceptors/reliability/index.js'
import type { LoopGuardPolicyInit } from '../../src/interceptors/reliability/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import { GavioRequest } from '../../src/request.js'
import { GavioResponse } from '../../src/response.js'
import { RootRunResolver } from '../../src/root-run.js'
import { ToolCall } from '../../src/types.js'

type Doc = Record<string, any> // eslint-disable-line @typescript-eslint/no-explicit-any

const ROOT = new URL('../../../../', import.meta.url)
const read = (relative: string): Doc =>
  JSON.parse(readFileSync(fileURLToPath(new URL(relative, ROOT)), 'utf8'))
const VECTORS = read('test-vectors/loop-guard/decisions.json')

// ---- shared vectors ------------------------------------------------------------------

describe('loop guard vectors — decisions', () => {
  for (const c of VECTORS['decisionCases'] as Doc[]) {
    it(c['id'], () => {
      const decision = evaluateLoopGuard({
        policy: c['policy'],
        state: LoopGuardState.fromWire(c['state']),
        runId: 'run-1',
        elapsedSeconds: c['state']['elapsedSeconds'],
      }) as unknown as Doc
      for (const [key, value] of Object.entries(c['expected'])) {
        expect(decision[key], key).toStrictEqual(value)
      }
      for (const key of (c['expectedAbsent'] ?? []) as string[]) expect(key in decision).toBe(false)
      expect(decision['policyId']).toBe(c['policy']['id'])
      expect(decision['runId']).toBe('run-1')
    })
  }
})

/** Play a run: decide before each call, record the calls that went through. */
function drive(c: Doc): Doc[] {
  const policy = loopGuardPolicy(c['policy'])
  const state = new LoopGuardState()
  const decisions: Doc[] = []
  let startedAt: number | null = null
  for (const call of c['calls'] as Doc[]) {
    startedAt ??= call['atSeconds'] as number
    const decision = evaluateLoopGuard({
      policy,
      state,
      runId: 'run-1',
      elapsedSeconds: (call['atSeconds'] as number) - startedAt,
    })
    decisions.push(decision as unknown as Doc)
    if (decision.action !== 'stop') {
      // a refused call never happened
      state.record(
        call['costUsd'],
        ((call['toolCalls'] ?? []) as Doc[]).map(
          (item, index) =>
            new ToolCall({ id: `c${index}`, name: item['name'], arguments: item['arguments'] }),
        ),
      )
    }
  }
  return decisions
}

describe('loop guard vectors — runs', () => {
  for (const c of VECTORS['runCases'] as Doc[]) {
    it(c['id'], () => {
      const decisions = drive(c)
      expect(decisions.map((d) => d['action'])).toStrictEqual(c['expectedActions'])
      expect(decisions.map((d) => d['breached'])).toStrictEqual(c['expectedBreached'])
      expect(decisions.map((d) => d['steps'])).toStrictEqual(c['expectedSteps'])
      expect(decisions.map((d) => d['costUsd'])).toStrictEqual(c['expectedCostUsd'])
      expect(decisions.map((d) => d['elapsedSeconds'])).toStrictEqual(c['expectedElapsedSeconds'])
      if ('expectedTargetModels' in c) {
        expect(decisions.map((d) => d['targetModel'] ?? null)).toStrictEqual(
          c['expectedTargetModels'],
        )
      }
    })
  }
})

describe('loop guard vectors — root runs', () => {
  for (const c of VECTORS['rootRunCases'] as Doc[]) {
    it(c['id'], () => {
      const resolver = 'capacity' in c ? new RootRunResolver(c['capacity']) : new RootRunResolver()
      const resolved = (c['calls'] as Doc[]).map((call) =>
        resolver.resolve(call['traceId'], call['parentTraceId'], call['metadata']),
      )
      expect(resolved).toStrictEqual(c['expectedRunIds'])
    })
  }
})

describe('loop guard vectors — invalid policies', () => {
  for (const c of VECTORS['policyErrorCases'] as Doc[]) {
    it(c['id'], () => {
      expect(c['expectedError']).toBe('Configuration')
      expect(() => loopGuardPolicy(c['policy'])).toThrow(ConfigurationError)
    })
  }
})

describe('spec conformance', () => {
  it('decisions and policies carry only keys the schemas define', () => {
    const decisionSchema = read('spec/LoopGuardDecision.schema.json')
    const policySchema = read('spec/LoopGuardPolicy.schema.json')
    const allowed = new Set(Object.keys(decisionSchema['properties']))
    for (const c of VECTORS['decisionCases'] as Doc[]) {
      const decision = evaluateLoopGuard({
        policy: c['policy'],
        state: LoopGuardState.fromWire(c['state']),
        runId: 'r',
        elapsedSeconds: c['state']['elapsedSeconds'],
      })
      const keys = Object.keys(decision)
      for (const required of decisionSchema['required']) expect(keys).toContain(required)
      for (const key of keys) expect(allowed.has(key), key).toBe(true)
      for (const key of Object.keys(c['policy'])) {
        expect(key in policySchema['properties'], key).toBe(true)
      }
    }
    expect(new Set(decisionSchema['properties']['breaches']['items']['enum'])).toStrictEqual(
      new Set(BREACH_ORDER),
    )
    expect(new Set(policySchema['required'])).toStrictEqual(new Set(['id', 'action']))
  })

  it('the audit schema defines the loopGuard entry the SDK writes', () => {
    const audit = read('spec/AuditRecord.schema.json')['properties']['loopGuard']
    const entry = audit['anyOf'][0]
    expect(new Set(entry['required'])).toStrictEqual(
      new Set(['policyId', 'runId', 'action', 'steps']),
    )
    const written = [
      'policyId',
      'runId',
      'action',
      'breached',
      'steps',
      'costUsd',
      'elapsedSeconds',
    ]
    for (const key of written) expect(key in entry['properties'], key).toBe(true)
    expect(entry['additionalProperties']).toBe(false)
  })
})

// ---- the interceptor ---------------------------------------------------------------------

const MESSAGES = [{ role: 'user', content: 'keep going' }]

/** A mock provider with a price per call, optional tool calls, and optional failures. */
function scripted(
  options: { costUsd?: number; toolCalls?: ToolCall[]; fail?: boolean } = {},
) {
  const base = mockProvider({ response: 'ok' })
  const models: string[] = []
  const adapter = {
    providerName: 'mock',
    async complete(request: GavioRequest): Promise<GavioResponse> {
      models.push(request.model)
      if (options.fail) throw new ProviderUnavailableError('down')
      const response = await base.complete(request)
      response.costUsd = options.costUsd ?? 0
      response.toolCalls = [...(options.toolCalls ?? [])]
      return response
    },
    async healthCheck(): Promise<boolean> {
      return true
    },
  }
  return { adapter, models }
}

class CollectingSink implements AuditSink {
  readonly records: AuditRecord[] = []
  async write(record: AuditRecord): Promise<void> {
    this.records.push(record)
  }
}

function gatewayFor(
  adapter: ReturnType<typeof scripted>['adapter'],
  guard: ReturnType<typeof loopGuard>,
  options: { sink?: AuditSink; dryRun?: boolean; hashChain?: boolean } = {},
): Gateway {
  const gateway = new Gateway({ adapter, model: 'mock', dryRun: options.dryRun ?? false })
  if (options.sink) {
    gateway.use(auditInterceptor({ sink: options.sink, hashChain: options.hashChain ?? false }))
  }
  return gateway.use(guard)
}

/** An agent loop: the first call is the root, every later call names it as its parent. */
async function agentLoop(gateway: Gateway, calls: number): Promise<GavioResponse[]> {
  const responses = [await gateway.complete({ messages: MESSAGES })]
  for (let i = 1; i < calls; i += 1) {
    responses.push(
      await gateway.complete({ messages: MESSAGES, parentTraceId: responses[0]!.traceId }),
    )
  }
  return responses
}

const policy = (init: Partial<LoopGuardPolicyInit> = {}): LoopGuardPolicyInit => ({
  id: 'agent',
  ...init,
})

async function caught(promise: Promise<unknown>): Promise<LoopGuardError> {
  try {
    await promise
  } catch (error) {
    expect(error).toBeInstanceOf(LoopGuardError)
    return error as LoopGuardError
  }
  throw new Error('expected a LoopGuardError')
}

describe('loop guard interceptor — stopping a runaway agent', () => {
  it('stops at 25 steps', async () => {
    const { adapter, models } = scripted()
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxSteps: 25 }) }))

    const stopped = await caught(agentLoop(gateway, 40))

    expect(models).toHaveLength(25) // the 26th call never reached the provider
    expect(stopped.decision['breached']).toBe('max_steps')
    expect(stopped.decision['steps']).toBe(25)
    expect(stopped.message).toContain('max_steps reached')
  })

  it('stops at a cost cap', async () => {
    const { adapter, models } = scripted({ costUsd: 0.4 })
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxCostUsd: 1.0 }) }))

    const stopped = await caught(agentLoop(gateway, 40))

    // $0.40 x 3 = $1.20 crosses the $1.00 cap; the call that crosses it still happened.
    expect(models).toHaveLength(3)
    expect(stopped.decision['breached']).toBe('max_cost')
    expect(stopped.decision['costUsd']).toBeCloseTo(1.2)
  })

  it('stops repeated tool calls without leaking the arguments', async () => {
    const secret = 'jan@example.com'
    const { adapter, models } = scripted({
      toolCalls: [new ToolCall({ id: 'c', name: 'lookup', arguments: { email: secret } })],
    })
    const gateway = gatewayFor(
      adapter,
      loopGuard({ policy: policy({ maxRepeatedToolCalls: 3 }) }),
    )

    const stopped = await caught(agentLoop(gateway, 10))

    expect(models).toHaveLength(3)
    expect((stopped.decision['repeatedToolCall'] as Doc)['name']).toBe('lookup')
    expect((stopped.decision['repeatedToolCall'] as Doc)['count']).toBe(3)
    expect(stopped.message).toContain("'lookup'")
    expect(stopped.message).not.toContain(secret)
    expect(JSON.stringify(stopped.decision)).not.toContain(secret)
  })

  it('measures wall time from the first call of the run', async () => {
    let now = 1000
    const { adapter } = scripted()
    const gateway = gatewayFor(
      adapter,
      loopGuard({ policy: policy({ maxWallTimeSeconds: 30 }), clock: () => now }),
    )

    const first = await gateway.complete({ messages: MESSAGES })
    now += 29.9
    await gateway.complete({ messages: MESSAGES, parentTraceId: first.traceId })
    now += 0.1
    const stopped = await caught(
      gateway.complete({ messages: MESSAGES, parentTraceId: first.traceId }),
    )
    expect(stopped.decision['breached']).toBe('max_wall_time')
  })

  it('counts the steps of failing calls too', async () => {
    const { adapter, models } = scripted({ fail: true })
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxSteps: 3 }) }))
    const call = () => gateway.complete({ messages: MESSAGES, metadata: { runId: 'r' } })

    for (let i = 0; i < 3; i += 1) await expect(call()).rejects.toBeInstanceOf(ProviderUnavailableError)
    await expect(call()).rejects.toBeInstanceOf(LoopGuardError)
    expect(models).toHaveLength(3)
  })
})

describe('loop guard interceptor — which calls belong to one run', () => {
  it('shares a run by explicit run id and keeps other runs independent', async () => {
    const { adapter } = scripted()
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxSteps: 2 }) }))

    await gateway.complete({ messages: MESSAGES, metadata: { runId: 'a' } })
    await gateway.complete({ messages: MESSAGES, metadata: { runId: 'a' } })
    await gateway.complete({ messages: MESSAGES, metadata: { runId: 'b' } }) // a fresh run
    await expect(
      gateway.complete({ messages: MESSAGES, metadata: { run_id: 'a' } }), // snake_case too
    ).rejects.toBeInstanceOf(LoopGuardError)
  })

  it('counts sub-agents under one root together', async () => {
    const { adapter } = scripted()
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxSteps: 3 }) }))
    const root = await gateway.complete({ messages: MESSAGES, agentId: 'orchestrator' })
    const child = (agentId: string) =>
      gateway.complete({ messages: MESSAGES, agentId, parentTraceId: root.traceId })
    await child('agent-a')
    await child('agent-b')
    await expect(child('agent-a')).rejects.toBeInstanceOf(LoopGuardError)
  })

  it('treats calls without a run id or parent as separate runs', async () => {
    const { adapter, models } = scripted()
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxSteps: 1 }) }))
    for (let i = 0; i < 5; i += 1) await gateway.complete({ messages: MESSAGES })
    expect(models).toHaveLength(5)
  })
})

describe('loop guard interceptor — actions', () => {
  it('reroutes to the fallback model and keeps the tools', async () => {
    const { adapter, models } = scripted()
    const seen: GavioRequest[] = []
    const spying = {
      ...adapter,
      async complete(request: GavioRequest) {
        seen.push(request)
        return adapter.complete(request)
      },
    }
    const gateway = gatewayFor(
      spying,
      loopGuard({
        policy: policy({ maxSteps: 2, action: 'fallback', fallbackModel: 'mock-mini' }),
      }),
    )
    const tools = [{ name: 'lookup' }]
    const root = await gateway.complete({ messages: MESSAGES, tools, toolChoice: 'auto' })
    const rest: GavioResponse[] = []
    for (let i = 0; i < 3; i += 1) {
      rest.push(
        await gateway.complete({
          messages: MESSAGES,
          parentTraceId: root.traceId,
          tools,
          toolChoice: 'auto',
        }),
      )
    }

    expect(models).toStrictEqual(['mock', 'mock', 'mock-mini', 'mock-mini'])
    expect([root, ...rest].map((r) => r.model)).toStrictEqual([
      'mock',
      'mock',
      'mock-mini',
      'mock-mini',
    ])
    // a rerouted request still declares its tools
    expect(seen[2]!.tools).toStrictEqual(tools)
    expect(seen[2]!.toolChoice).toBe('auto')
  })

  it('dry_run records the breach and lets every call through', async () => {
    const sink = new CollectingSink()
    const { adapter, models } = scripted()
    const gateway = gatewayFor(
      adapter,
      loopGuard({ policy: policy({ maxSteps: 2, action: 'dry_run' }) }),
      { sink },
    )

    await agentLoop(gateway, 4)

    expect(models).toHaveLength(4)
    const verdicts = sink.records.map((r) => r.loopGuard!)
    expect(verdicts.map((v) => v.action)).toStrictEqual(['allow', 'allow', 'dry_run', 'dry_run'])
    expect(verdicts.map((v) => v.breached)).toStrictEqual([null, null, 'max_steps', 'max_steps'])
  })

  it('a gateway dry run never stops or reroutes', async () => {
    const sink = new CollectingSink()
    const { adapter, models } = scripted()
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxSteps: 1 }) }), {
      sink,
      dryRun: true,
    })

    await agentLoop(gateway, 3)

    expect(models).toHaveLength(3)
    expect(sink.records.map((r) => r.loopGuard!.action)).toStrictEqual([
      'allow',
      'dry_run',
      'dry_run',
    ])
  })
})

describe('loop guard interceptor — audit, inspector, governance', () => {
  it('the audit record carries counts only', async () => {
    const sink = new CollectingSink()
    const secret = 'jan@example.com'
    const { adapter } = scripted({
      costUsd: 0.25,
      toolCalls: [new ToolCall({ id: 'c', name: 'lookup', arguments: { e: secret } })],
    })
    const gateway = gatewayFor(adapter, loopGuard({ policy: policy({ maxSteps: 10 }) }), { sink })

    await agentLoop(gateway, 3)

    const third = sink.records[2]!.loopGuard!
    expect(third).toStrictEqual({
      policyId: 'agent',
      runId: sink.records[0]!.traceId,
      action: 'allow',
      breached: null,
      steps: 2,
      costUsd: 0.5,
      elapsedSeconds: third.elapsedSeconds,
    })
    expect('loopGuard' in sink.records[2]!.toJSON()).toBe(true)
    expect(JSON.stringify(sink.records[2]!.toJSON())).not.toContain(secret)
  })

  it('has no loopGuard key without a guard, and chains verify either way', async () => {
    const plain = new CollectingSink()
    const noGuard = new Gateway({ adapter: scripted().adapter, model: 'mock' }).use(
      auditInterceptor({ sink: plain, hashChain: true }),
    )
    await noGuard.complete({ messages: MESSAGES })
    await noGuard.complete({ messages: MESSAGES })
    expect(plain.records.every((r) => !('loopGuard' in r.toJSON()))).toBe(true)
    expect(verifyChain(plain.records)).toBe(true)

    const guarded = new CollectingSink()
    const gateway = gatewayFor(
      scripted().adapter,
      loopGuard({ policy: policy({ maxSteps: 10 }) }),
      { sink: guarded, hashChain: true },
    )
    await agentLoop(gateway, 3)
    expect(verifyChain(guarded.records)).toBe(true)
    guarded.records[1]!.loopGuard!.steps = 99
    expect(verifyChain(guarded.records)).toBe(false)
  })

  it('reports a breach to the inspector and as a governance event', async () => {
    const guard = loopGuard({ policy: policy({ maxSteps: 1, action: 'dry_run' }) })
    const request = new GavioRequest({ messages: MESSAGES, model: 'mock', provider: 'mock' })
    const ctx = new InterceptorContext({ traceId: request.traceId })

    await guard.before!(request, ctx) // step 1: within limits
    expect(ctx.loopGuard!.breached).toBeNull()
    expect(ctx.drainGovernanceEvents()).toStrictEqual([])

    const again = new GavioRequest({
      messages: MESSAGES,
      model: 'mock',
      provider: 'mock',
      parentTraceId: request.traceId,
    })
    const ctx2 = new InterceptorContext({ traceId: again.traceId })
    await guard.before!(again, ctx2)
    expect((ctx2.drainInspectEntries()!['loop_guard'] as Doc)['breached']).toBe('max_steps')
    const events = ctx2.drainGovernanceEvents()
    expect(events.map((e) => e['kind'])).toStrictEqual(['loop_guard'])
    expect(events[0]!['policyId']).toBe('agent')
    expect(ctx2.interceptorsFired).toContain('loop_guard')
  })
})

describe('loop guard store', () => {
  it('evicts the least recently used run', () => {
    const store = new LoopGuardStore({ maxRuns: 2, clock: () => 0 })
    const a = store.acquire('a')
    store.acquire('b')
    store.acquire('a') // refresh a
    store.acquire('c') // evicts b
    expect(store.size).toBe(2)
    expect(store.get('b')).toBeUndefined()
    expect(store.get('a')).toBe(a)
  })

  it('forgets idle runs', () => {
    let now = 1000
    const store = new LoopGuardStore({ ttlSeconds: 60, clock: () => now })
    const first = store.acquire('a')
    first.startCall()
    now += 59
    expect(store.get('a')).toBe(first) // reading refreshes it
    now += 59
    expect(store.get('a')).toBe(first)
    now += 61
    expect(store.get('a')).toBeUndefined()
    const restarted = store.acquire('a')
    expect(restarted).not.toBe(first)
    expect(restarted.steps).toBe(0)
    expect(restarted.startedAt).toBe(now)
  })

  it('validates its bounds', () => {
    for (const bad of [0, -1, 1.5]) {
      expect(() => new LoopGuardStore({ maxRuns: bad })).toThrow(ConfigurationError)
    }
    expect(() => new LoopGuardStore({ ttlSeconds: 0 })).toThrow(ConfigurationError)
  })

  it('a run that expires mid-call does not break the response', async () => {
    let now = 1000
    const store = new LoopGuardStore({ ttlSeconds: 10, clock: () => now })
    const guard = loopGuard({ policy: policy({ maxSteps: 5 }), store, clock: () => now })
    const request = new GavioRequest({ messages: MESSAGES, model: 'mock', provider: 'mock' })
    const ctx = new InterceptorContext({ traceId: request.traceId })
    await guard.before!(request, ctx)
    now += 100 // the run idles out while the provider call is in flight
    const response = new GavioResponse({
      traceId: request.traceId,
      content: 'x',
      model: 'mock',
      provider: 'mock',
    })
    expect(await guard.after!(response, ctx)).toBe(response)
  })
})

describe('policy and resolver validation', () => {
  it('accepts a normalised policy and rejects a bad one', () => {
    expect(loopGuardPolicy({ id: 'p', maxSteps: 3 }).maxSteps).toBe(3)
    expect(loopGuardPolicy({ id: 'p' }).action).toBe('stop')
    expect(() => loopGuard({ policy: { id: 'p', maxSteps: 0 } })).toThrow(ConfigurationError)
    // loopGuardPolicy is idempotent
    const once = loopGuardPolicy({ id: 'p', maxSteps: 3, action: 'dry_run' })
    expect(loopGuardPolicy(once)).toStrictEqual(once)
    expect(() => loopGuardPolicy({ id: 'p', maxSteps: true as never })).toThrow(ConfigurationError)
  })

  it('the resolver is bounded and validated', () => {
    const resolver = new RootRunResolver(3)
    for (let i = 0; i < 10; i += 1) resolver.resolve(`t${i}`)
    expect(resolver.size).toBe(3)
    for (const bad of [0, -1, 1.5]) expect(() => new RootRunResolver(bad)).toThrow(ConfigurationError)
  })

  it('state records tool calls by hash and keeps first-seen order', () => {
    const state = new LoopGuardState()
    state.record(0.1, [
      new ToolCall({ id: 'a', name: 'search', arguments: { q: 'x', n: 1 } }),
      new ToolCall({ id: 'b', name: 'read', arguments: { q: 'x' } }),
    ])
    state.record(0.2, [new ToolCall({ id: 'c', name: 'search', arguments: { n: 1, q: 'x' } })])
    expect(state.steps).toBe(2)
    expect(state.costUsd).toBeCloseTo(0.3)
    expect([...state.toolCalls.values()].map((e) => [e.name, e.count])).toStrictEqual([
      ['search', 2],
      ['read', 1],
    ])
  })
})
