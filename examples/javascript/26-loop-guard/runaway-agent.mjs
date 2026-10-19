// Gavio loop guard - stop a runaway agent (F-REL-09).
//
//   npm install
//   node runaway-agent.mjs
//
// A planner agent that never decides it is done keeps calling the model. The loop guard
// counts the calls of one root run and refuses the next one once a limit is reached: first
// the 25-step cap, then a cost cap. Offline: the mock provider, no key, no network.

import assert from 'node:assert/strict'
import { Gateway, LoopGuardError, PricingProvider } from 'gavio'
import { loopGuard } from 'gavio/interceptors/reliability'
import { mockProvider } from 'gavio/testing'

const MODEL = 'demo-agent'
// Both strings are 40 characters, about 10 tokens each. At $6 per 1,000 tokens every call
// costs $0.12, so the cost cap below is reached after a handful of calls.
const PROMPT = 'Plan the next step of the task, then act'
const REPLY = 'Step done; planning the next step now...'
const PRICES = { [MODEL]: [6.0, 6.0] }

async function runAgent(title, policy, expectCalls, expectBreach) {
  const gateway = new Gateway({ model: MODEL })
    .withAdapter(mockProvider({ response: REPLY, pricing: new PricingProvider(PRICES) }))
    .use(loopGuard({ policy }))
  const messages = [{ role: 'user', content: PROMPT }]

  // The agent loop. The first call is the root of the run; every later call names it as
  // its parentTraceId, so the guard counts all of them as one run.
  const root = await gateway.complete({ messages, agentId: 'planner' })
  let calls = 1
  let decision
  try {
    for (;;) {
      // a runaway agent: nothing in the loop ever decides to stop
      await gateway.complete({ messages, agentId: 'planner', parentTraceId: root.traceId })
      calls += 1
    }
  } catch (error) {
    if (!(error instanceof LoopGuardError)) throw error
    decision = error.decision // metadata only: counts, never prompts or tool arguments
  }

  console.log(`== ${title}`)
  console.log(`provider calls : ${calls}`)
  console.log(`stop reason    : ${decision.breached} (${decision.reason}, action ${decision.action})`)
  console.log(`run so far     : ${decision.steps} steps, $${decision.costUsd.toFixed(2)}`)
  console.log(`same run       : ${decision.runId === root.traceId ? 'yes' : 'no'}`)
  console.log()

  // The example doubles as a CI check: fail if the guard stopped the run anywhere else.
  assert.deepEqual([calls, decision.breached], [expectCalls, expectBreach])
}

await runAgent('Step cap: at most 25 calls per run', { id: 'planner-steps', maxSteps: 25 }, 25, 'max_steps')
// $0.12 a call: the 9th call crosses $1.00 and still happens
await runAgent(
  'Cost cap: $1.00 per run (the 25-step cap is still set)',
  { id: 'planner-budget', maxSteps: 25, maxCostUsd: 1.0 },
  9,
  'max_cost',
)
