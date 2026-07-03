// Gavio Inspector — trace a multi-agent flow in the web UI (plain JS, ESM).
//
// Dev mode + inspect: true serves the Inspector at http://127.0.0.1:7411 while
// a small orchestrator → researcher/writer flow runs through the gateway.
// No API key, no network beyond localhost.
//
//   npm install
//   node inspector.mjs

import process from 'node:process'
import readline from 'node:readline/promises'
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

// Dev mode = mock provider + stdout audit; inspect: true = UI on port 7411.
const gw = new Gateway({ devMode: true, inspect: true }).use(piiGuard())

const session = 'sess-inspector-demo'

// Orchestrator call — the root of the agent DAG.
const plan = await gw.complete({
  messages: [{ role: 'user', content: 'Plan a briefing for jan@example.com' }],
  agentId: 'orchestrator',
  sessionId: session,
})

// Two child agents — parentTraceId links them under the orchestrator.
for (const [agentId, task] of [
  ['researcher', 'Collect facts for the briefing (IBAN NL91ABNA0417164300 on file)'],
  ['writer', 'Draft the briefing from the research notes'],
]) {
  await gw.complete({
    messages: [{ role: 'user', content: task }],
    agentId,
    parentTraceId: plan.traceId,
    sessionId: session,
  })
}

console.log('\nInspector: http://127.0.0.1:7411')
console.log('  · Traces   — waterfall per request: interceptor spans + the provider call')
console.log('  · a trace  — the PII diff: original vs redacted, side by side')
console.log('  · DAG      — orchestrator → researcher/writer graph with cost rollups')
console.log('  · Sessions — ' + session + ' with per-session totals')

const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
await rl.question('\nOpen the UI, then press Enter to exit... ')
rl.close()
