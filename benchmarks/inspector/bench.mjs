/**
 * Inspector overhead benchmark — JavaScript SDK (INSPECTOR_PLAN §13).
 *
 * Same methodology as bench.py: per-request latency through the gateway with
 * the inspector disabled (baseline), in metadata mode, and in full mode —
 * bus + ring buffer + emitter only, no HTTP server. A delay-padded mock
 * provider emulates a real call and one mutating interceptor makes full mode
 * pay for its diff computation.
 *
 * Requires the built SDK:  cd packages/gavio-js && npm ci && npm run build
 * Run from the repo root:  node benchmarks/inspector/bench.mjs
 */

import { Gateway } from '../../packages/gavio-js/dist/esm/index.js'
import { mockProvider } from '../../packages/gavio-js/dist/esm/providers/mock.js'

const SIMULATED_DELAY_MS = 5.0
const WARMUP = 20
const ITERATIONS = 200
const METADATA_BUDGET_PCT = 10.0 // of the simulated provider call
const FULL_BUDGET_PCT = 25.0

const MESSAGES = [{ role: 'user', content: 'benchmark the inspector overhead '.repeat(8) }]

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/** MockProvider padded with a fixed delay to emulate a real provider call. */
function delayedMockProvider() {
  const base = mockProvider()
  const delayed = Object.create(base)
  delayed.complete = async (request) => {
    await sleep(SIMULATED_DELAY_MS)
    return base.complete(request)
  }
  return delayed
}

/** Mutates every request so full mode computes a mutation diff. */
const annotator = {
  name: 'annotator',
  before(request) {
    const messages = request.messages.map((m) => ({ ...m }))
    messages[0].content = messages[0].content + ' ·'
    return request.copyWithMessages(messages)
  },
}

function buildGateway(mode) {
  const gw = new Gateway({
    model: 'mock',
    inspect: mode
      ? { mode, startServer: false, unsafeContentCaptureAck: true }
      : undefined,
  })
  gw.use(annotator)
  return gw.withAdapter(delayedMockProvider())
}

async function measure(gateway) {
  const samplesUs = []
  for (let i = 0; i < WARMUP + ITERATIONS; i++) {
    const started = process.hrtime.bigint()
    await gateway.complete({ messages: MESSAGES })
    const elapsedUs = Number(process.hrtime.bigint() - started) / 1000
    if (i >= WARMUP) samplesUs.push(elapsedUs)
  }
  return samplesUs
}

function summarize(samplesUs) {
  const ordered = [...samplesUs].sort((a, b) => a - b)
  const at = (q) => ordered[Math.max(0, Math.ceil(q * ordered.length) - 1)]
  return { p50Us: Math.round(at(0.5) * 10) / 10, p95Us: Math.round(at(0.95) * 10) / 10 }
}

const results = {}
for (const [label, mode] of [
  ['disabled', null],
  ['metadata', 'metadata'],
  ['full', 'full'],
]) {
  results[label] = summarize(await measure(buildGateway(mode)))
}

const delayUs = SIMULATED_DELAY_MS * 1000
const baseline = results.disabled.p50Us
for (const [label, budget] of [
  ['metadata', METADATA_BUDGET_PCT],
  ['full', FULL_BUDGET_PCT],
]) {
  const overheadUs = Math.round((results[label].p50Us - baseline) * 10) / 10
  const overheadPct = Math.round((overheadUs / delayUs) * 10000) / 100
  results[label].overheadP50Us = overheadUs
  results[label].overheadPct = overheadPct
  results[label].budgetPct = budget
  results[label].pass = overheadPct < budget
}

const pass = results.metadata.pass && results.full.pass
console.log(
  JSON.stringify(
    {
      benchmark: 'inspector-overhead',
      sdk: 'js',
      simulatedDelayMs: SIMULATED_DELAY_MS,
      iterations: ITERATIONS,
      results,
      pass,
    },
    null,
    2,
  ),
)
process.exit(pass ? 0 : 1)
