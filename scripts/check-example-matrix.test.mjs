// Run with: node --test scripts/check-example-matrix.test.mjs
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { checkExampleMatrix } from './check-example-matrix.mjs'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

function write(root, rel, text) {
  const file = join(root, rel)
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, text)
}

function readme(rows) {
  return [
    '| # | Scenario | Python | JavaScript | Java | Needs a key? |',
    '|---|---|---|---|---|---|',
    ...rows,
    '',
  ].join('\n')
}

function fixture(mutate = {}) {
  const root = mkdtempSync(join(tmpdir(), 'gavio-matrix-'))
  const matrix = {
    schemaVersion: 'gavio.example-matrix.v1',
    sdks: ['python', 'javascript', 'java'],
    examples: [
      {
        id: '01',
        title: 'Quickstart',
        needsKey: 'no',
        python: { path: 'examples/python/01-quickstart' },
        javascript: { path: 'examples/javascript/01-quickstart' },
        java: { path: 'examples/java/01-quickstart' },
      },
      {
        id: '04',
        title: 'Stack',
        needsKey: 'no',
        python: { path: 'examples/python/04-stack' },
        javascript: { status: 'planned', reason: 'port pending' },
        java: { status: 'n/a', reason: 'no equivalent' },
      },
    ],
  }
  for (const dir of [
    'examples/python/01-quickstart',
    'examples/javascript/01-quickstart',
    'examples/java/01-quickstart',
    'examples/python/04-stack',
  ]) {
    write(root, `${dir}/README.md`, '# example\n')
  }
  const rootRows = [
    '| 01 | Quickstart | [py](./examples/python/01-quickstart/) | [js](./examples/javascript/01-quickstart/) | [java](./examples/java/01-quickstart/) | no |',
    '| 04 | Stack | [py](./examples/python/04-stack/) | — | — | no |',
  ]
  const examplesRows = [
    '| 01 | Quickstart | [py](./python/01-quickstart/) | [js](./javascript/01-quickstart/) | [java](./java/01-quickstart/) | no |',
    '| 04 | Stack | [py](./python/04-stack/) | — | — | no |',
  ]
  const state = { matrix, rootRows, examplesRows }
  mutate.before?.(state, root)
  write(root, 'examples/matrix.json', JSON.stringify(state.matrix, null, 2))
  write(root, 'README.md', readme(state.rootRows))
  write(root, 'examples/README.md', readme(state.examplesRows))
  mutate.after?.(state, root)
  return root
}

function errorsFor(mutate) {
  const root = fixture(mutate)
  try {
    return checkExampleMatrix(root).errors
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
}

test('consistent matrix and READMEs pass', () => {
  assert.deepEqual(errorsFor({}), [])
})

test('the repository matrix is consistent', () => {
  assert.deepEqual(checkExampleMatrix(repoRoot).errors, [])
})

test('fails when a listed path is missing', () => {
  const errors = errorsFor({
    before: ({ matrix }) => {
      matrix.examples[0].java.path = 'examples/java/01-missing'
    },
  })
  assert.ok(errors.some((error) => error.includes('listed path does not exist: examples/java/01-missing')), errors.join('\n'))
})

test('fails when a gap is not listed', () => {
  const errors = errorsFor({
    before: ({ matrix }) => {
      delete matrix.examples[1].java
    },
  })
  assert.ok(errors.some((error) => error.includes('example 04 (java): gap is not listed')), errors.join('\n'))
})

test('fails when a planned gap has no reason', () => {
  const errors = errorsFor({
    before: ({ matrix }) => {
      matrix.examples[1].javascript = { status: 'planned' }
    },
  })
  assert.ok(errors.some((error) => error.includes('planned entries need a reason')), errors.join('\n'))
})

test('fails when an example directory exists but is not listed', () => {
  const errors = errorsFor({
    after: (_state, root) => write(root, 'examples/javascript/09-extra/README.md', '# extra\n'),
  })
  assert.ok(errors.some((error) => error.includes('examples/javascript/09-extra exists but is not listed')), errors.join('\n'))
})

test('fails when a planned entry already has a directory', () => {
  const errors = errorsFor({
    after: (_state, root) => write(root, 'examples/javascript/04-stack/README.md', '# early\n'),
  })
  assert.ok(errors.some((error) => error.includes('listed as planned but examples/javascript/04-stack exists')), errors.join('\n'))
})

test('fails when README.md disagrees with the matrix', () => {
  const errors = errorsFor({
    before: (state) => {
      state.rootRows[1] = state.rootRows[1].replace('| — | — |', '| [js](./examples/javascript/04-stack/) | — |')
    },
  })
  assert.ok(errors.some((error) => error.startsWith('README.md: example 04 javascript is planned')), errors.join('\n'))
})

test('fails when examples/README.md links the wrong path', () => {
  const errors = errorsFor({
    before: (state) => {
      state.examplesRows[0] = state.examplesRows[0].replace('./java/01-quickstart/', './java/02-quickstart/')
    },
  })
  assert.ok(errors.some((error) => error.startsWith('examples/README.md: example 01 java links')), errors.join('\n'))
})

test('fails when a table row is missing or has a different key requirement', () => {
  const errors = errorsFor({
    before: (state) => {
      state.examplesRows.pop()
      state.rootRows[0] = state.rootRows[0].replace('| no |', '| optional |')
    },
  })
  assert.ok(errors.some((error) => error.includes('example 04 is in examples/matrix.json but missing from the table')), errors.join('\n'))
  assert.ok(errors.some((error) => error.includes('"Needs a key?" is "optional"')), errors.join('\n'))
})

test('the committed matrix lists every gap with a reason', () => {
  const matrix = JSON.parse(readFileSync(join(repoRoot, 'examples/matrix.json'), 'utf8'))
  for (const example of matrix.examples) {
    for (const sdk of matrix.sdks) {
      const entry = example[sdk]
      assert.ok(entry.path || entry.reason, `${example.id} ${sdk}`)
    }
  }
})
