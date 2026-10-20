// Run with: node --test scripts/gen-benchmark-results.test.mjs
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { buildResults } from './gen-benchmark-results.mjs'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

function run(overrides = {}) {
  return {
    sdk: 'python',
    gavioVersion: '9.9.9',
    date: '2030-01-02T03:04:05Z',
    seed: 7,
    pass: true,
    methodology: {
      simulatedDelayMs: 5,
      warmup: 20,
      iterations: 200,
      percentile: 'nearest-rank',
      interleaved: true,
      payloadCount: 10,
      payloadDigest: 'sha256:abc',
    },
    runner: {
      os: 'linux',
      osRelease: '6.1',
      arch: 'x64',
      cpuModel: 'Test CPU',
      cpuCount: 4,
      memoryGb: 8,
      runtime: { name: 'CPython', version: '3.12.0' },
      ci: true,
      ciProvider: 'github-actions',
      ciImage: 'ubuntu-24.04',
    },
    configurations: [
      {
        configuration: 'baseline',
        description: 'Raw provider call, no gateway',
        p50Us: 5000,
        p95Us: 5100.25,
        overheadP50Us: 0,
        overheadPct: 0,
        budgetPct: null,
        pass: true,
      },
      {
        configuration: 'audit',
        description: 'Audit only',
        p50Us: 5100.04,
        p95Us: 5200,
        overheadP50Us: 100.04,
        overheadPct: 2.0008,
        budgetPct: 20,
        pass: false,
      },
    ],
    ...overrides,
  }
}

function latest(runs = [run()]) {
  return { schemaVersion: 1, benchmark: 'chain-overhead', gavioVersion: '9.9.9', generatedAt: '2030-01-02T03:04:06Z', pass: true, runs }
}

test('every number in the table comes from latest.json', () => {
  const text = buildResults(latest())
  assert.match(text, /## Python\n\nGavio 9\.9\.9 · 2030-01-02T03:04:05Z · seed 7 · pass/)
  assert.match(text, /\| `baseline` \| Raw provider call, no gateway \| 5000\.0 \| 5100\.3 \| 0\.0 \| 0\.00% \| — \| pass \|/)
  assert.match(text, /\| `audit` \| Audit only \| 5100\.0 \| 5200\.0 \| 100\.0 \| 2\.00% \| 20\.00% \| \*\*FAIL\*\* \|/)
  assert.match(text, /simulates 5 ms/)
  assert.match(text, /200 interleaved iterations after 20 warm-up iterations, over 10 fixed payloads \(`sha256:abc`\)/)
})

test('the runner line states what was recorded, and whether it was CI', () => {
  assert.match(
    buildResults(latest()),
    /linux \(release 6\.1\), x64 · Test CPU \(4 CPUs, 8 GB\) · CPython 3\.12\.0 · CI run \(github-actions, ubuntu-24\.04\)/,
  )
  const local = run()
  local.runner = { ...local.runner, ci: false, ciProvider: null, ciImage: null }
  assert.match(buildResults(latest([local])), /· local run, not CI/)
})

test('a changed number changes the output, so a stale file is detected', () => {
  const changed = run()
  changed.configurations[1].p50Us = 5101
  assert.notEqual(buildResults(latest()), buildResults(latest([changed])))
})

test('runs keep the order of latest.json and unknown SDKs keep their id', () => {
  const text = buildResults(latest([run({ sdk: 'js' }), run({ sdk: 'java' }), run({ sdk: 'go' })]))
  const headings = [...text.matchAll(/^## (.+)$/gm)].map((m) => m[1])
  assert.deepEqual(headings, ['JavaScript', 'Java', 'go'])
})

test('the output ends with one newline and has no triple blank lines', () => {
  const text = buildResults(latest())
  assert.ok(text.endsWith('|\n'))
  assert.ok(!text.includes('\n\n\n'))
})

test('benchmarks/RESULTS.md is what benchmarks/latest.json generates', () => {
  const source = JSON.parse(readFileSync(resolve(repoRoot, 'benchmarks/latest.json'), 'utf8'))
  const committed = readFileSync(resolve(repoRoot, 'benchmarks/RESULTS.md'), 'utf8')
  assert.equal(committed, buildResults(source))
})
