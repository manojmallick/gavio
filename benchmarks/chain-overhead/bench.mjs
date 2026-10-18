/**
 * Interceptor-chain overhead benchmark — JavaScript SDK (F-TRUST-02).
 *
 * Same methodology as bench.py: a MockProvider padded with a fixed 5 ms delay,
 * a raw-provider baseline versus realistic default interceptor stacks, seeded
 * payloads (identical across SDKs), interleaved round-robin measurement.
 * Configurations, thresholds, seed and payload recipe come from spec.json.
 *
 * Prints the run as JSON on stdout (and to --out). Exits non-zero when a CI
 * threshold is breached; those are deliberately loose, so a breach means a
 * structural regression rather than noise.
 *
 * Requires the built SDK:  cd packages/gavio-js && npm ci && npm run build
 * Run from the repo root:  node benchmarks/chain-overhead/bench.mjs
 */

import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { Gateway, GavioRequest, VERSION } from '../../packages/gavio-js/dist/esm/index.js'
import { auditInterceptor } from '../../packages/gavio-js/dist/esm/interceptors/audit/index.js'
import { rateLimiter } from '../../packages/gavio-js/dist/esm/interceptors/governance/index.js'
import { guardrails, regexDenylist } from '../../packages/gavio-js/dist/esm/interceptors/guardrails/index.js'
import { piiGuard } from '../../packages/gavio-js/dist/esm/interceptors/pii/index.js'
import { mockProvider } from '../../packages/gavio-js/dist/esm/providers/mock.js'

const SCHEMA_VERSION = 1
const SDK = 'js'
const SPEC = JSON.parse(readFileSync(new URL('./spec.json', import.meta.url), 'utf8'))
const SIMULATED_DELAY_MS = SPEC.simulatedDelayMs

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ── Seeded payload generator (recipe shared by all three harnesses) ─────────

/** mulberry32 — a 32-bit PRNG that is trivial to port exactly. */
function mulberry32(seed) {
  let a = seed | 0
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return (t ^ (t >>> 14)) >>> 0
  }
}

function buildPayloads(seed, count) {
  const spec = SPEC.payload
  const vocab = spec.vocabulary
  const next = mulberry32(seed)
  const payloads = []
  for (let i = 0; i < count; i++) {
    const nWords = spec.wordCountMin + (next() % spec.wordCountSpan)
    const words = []
    for (let w = 0; w < nWords; w++) words.push(vocab[next() % vocab.length])
    const kind = next() % 4 // 0,1 clean · 2 email · 3 email + IBAN + phone
    const entities = []
    if (kind >= 2) {
      const n = next() % spec.emailModulus
      entities.push(`${spec.emailPrefix}${n}@${spec.emailDomain}`)
    }
    if (kind === 3) entities.push(spec.iban, spec.phone)
    for (const entity of entities) words.splice(next() % (words.length + 1), 0, entity)
    payloads.push(words.join(' '))
  }
  return payloads
}

const payloadDigest = (payloads) =>
  'sha256:' + createHash('sha256').update(payloads.join('\n'), 'utf8').digest('hex')

// ── Provider, sink, and per-configuration subjects ──────────────────────────

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

/** Discards records (no I/O noise) but proves the audit path really ran. */
function countingSink() {
  const sink = {
    records: 0,
    piiEntities: 0,
    async write(record) {
      sink.records += 1
      for (const n of Object.values(record.piiEntityCounts)) sink.piiEntities += n
    },
  }
  return sink
}

function buildInterceptor(name, sink) {
  switch (name) {
    case 'audit':
      return auditInterceptor({ sink })
    case 'pii_guard':
      return piiGuard(SPEC.piiGuard)
    case 'rate_limiter':
      return rateLimiter(SPEC.rateLimiter)
    case 'guardrails':
      return guardrails({ validators: [regexDenylist(SPEC.guardrailsDenylist)] })
    default:
      throw new Error(`unknown interceptor in spec.json: ${name}`)
  }
}

function buildSubject(config) {
  const adapter = delayedMockProvider()
  if (!config.gateway) {
    return {
      call: (messages) =>
        adapter.complete(new GavioRequest({ messages, model: 'mock', provider: 'mock' })),
      sink: null,
    }
  }
  const sink = countingSink()
  const gateway = new Gateway({ model: 'mock' })
  for (const name of config.interceptors) gateway.use(buildInterceptor(name, sink))
  gateway.withAdapter(adapter)
  return {
    call: (messages) => gateway.complete({ messages }),
    sink: config.interceptors.includes('audit') ? sink : null,
  }
}

// ── Measurement ─────────────────────────────────────────────────────────────

/**
 * Interleaved round-robin: every iteration times each configuration once. The
 * same payload is used for all configurations in an iteration and the starting
 * configuration rotates, so clock drift, GC and thermal effects hit every
 * configuration equally instead of biasing whichever ran last.
 */
async function measure(subjects, payloads, warmup) {
  const names = Object.keys(subjects)
  const samples = Object.fromEntries(names.map((n) => [n, []]))
  for (let i = 0; i < payloads.length; i++) {
    const messages = [{ role: 'user', content: payloads[i] }]
    for (let j = 0; j < names.length; j++) {
      const name = names[(i + j) % names.length]
      const started = process.hrtime.bigint()
      await subjects[name].call(messages)
      const elapsedUs = Number(process.hrtime.bigint() - started) / 1000
      if (i >= warmup) samples[name].push(elapsedUs)
    }
  }
  return samples
}

/** Nearest-rank percentile: ordered[ceil(q * n) - 1]. */
function percentile(samples, q) {
  const ordered = [...samples].sort((a, b) => a - b)
  return ordered[Math.max(0, Math.ceil(q * ordered.length) - 1)]
}

const round = (value, places) => {
  const f = 10 ** places
  return Math.round(value * f) / f
}

// ── Runner / hardware info ──────────────────────────────────────────────────

function runnerInfo() {
  const families = { darwin: 'macos', linux: 'linux', win32: 'windows' }
  const arches = { arm64: 'arm64', x64: 'x86_64' }
  const inCi = process.env.GITHUB_ACTIONS === 'true'
  const image = [process.env.ImageOS, process.env.ImageVersion].filter(Boolean).join('/')
  const cpus = os.cpus()
  return {
    os: families[process.platform] ?? process.platform,
    osRelease: os.release(),
    arch: arches[process.arch] ?? process.arch,
    cpuModel: cpus.length > 0 ? cpus[0].model.trim() : 'unknown',
    cpuCount: cpus.length,
    memoryGb: round(os.totalmem() / 1024 ** 3, 1),
    runtime: { name: 'Node.js', version: process.versions.node },
    ci: inCi,
    ciProvider: inCi ? 'github-actions' : null,
    ciImage: inCi ? image || null : null,
  }
}

// ── Entry point ─────────────────────────────────────────────────────────────

async function run(seed, warmup, iterations) {
  const payloads = buildPayloads(seed, warmup + iterations)
  const subjects = Object.fromEntries(SPEC.configurations.map((c) => [c.id, buildSubject(c)]))
  const samples = await measure(subjects, payloads, warmup)

  const delayUs = SIMULATED_DELAY_MS * 1000
  const baselineP50 = round(percentile(samples.baseline, 0.5), 1)
  const configurations = []
  let ok = true
  for (const config of SPEC.configurations) {
    const name = config.id
    const budget = config.budgetPct
    const p50 = round(percentile(samples[name], 0.5), 1)
    const p95 = round(percentile(samples[name], 0.95), 1)
    const overheadUs = round(p50 - baselineP50, 1)
    const overheadPct = round((overheadUs / delayUs) * 100, 2)
    let pass = budget === null || overheadPct < budget

    // Prove the stack really ran: every call audited, PII really detected.
    const sink = subjects[name].sink
    const checks = {}
    if (sink !== null) {
      checks.auditRecords = sink.records
      checks.piiEntities = sink.piiEntities
      pass = pass && sink.records === payloads.length
      if (config.interceptors.includes('pii_guard')) pass = pass && sink.piiEntities > 0
    }

    ok = ok && pass
    configurations.push({
      configuration: name,
      description: config.description,
      interceptors: config.interceptors,
      p50Us: p50,
      p95Us: p95,
      overheadP50Us: overheadUs,
      overheadPct,
      budgetPct: budget,
      checks,
      pass,
    })
  }

  return {
    schemaVersion: SCHEMA_VERSION,
    benchmark: 'chain-overhead',
    sdk: SDK,
    gavioVersion: VERSION,
    date: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    seed,
    methodology: {
      simulatedDelayMs: SIMULATED_DELAY_MS,
      warmup,
      iterations,
      percentile: 'nearest-rank',
      interleaved: true,
      payloadCount: payloads.length,
      payloadDigest: payloadDigest(payloads),
    },
    runner: runnerInfo(),
    configurations,
    pass: ok,
  }
}

function parseArgs(argv) {
  const args = { seed: SPEC.seed, warmup: SPEC.warmup, iterations: SPEC.iterations, out: null }
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i]
    const value = argv[++i]
    if (flag === '--seed') args.seed = Number.parseInt(value, 10)
    else if (flag === '--warmup') args.warmup = Number.parseInt(value, 10)
    else if (flag === '--iterations') args.iterations = Number.parseInt(value, 10)
    else if (flag === '--out') args.out = resolve(value)
    else throw new Error(`unknown argument: ${flag}`)
  }
  return args
}

const args = parseArgs(process.argv.slice(2))
const result = await run(args.seed, args.warmup, args.iterations)
const text = JSON.stringify(result, null, 2)
console.log(text)
if (args.out) {
  mkdirSync(dirname(args.out), { recursive: true })
  writeFileSync(args.out, text + '\n', 'utf8')
}
process.exit(result.pass ? 0 : 1)
