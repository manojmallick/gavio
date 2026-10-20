// Run with: node --test scripts/check-docs-truth.test.mjs
import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

import { checkDocsTruth } from './check-docs-truth.mjs'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

function baseFiles() {
  return {
    'packages/gavio-py/pyproject.toml': '[project]\nname = "gavio"\nversion = "1.2.3"\n',
    'CHANGELOG.md': [
      '# Changelog',
      '## [Unreleased]',
      '## [1.2.3] — 2026-01-03',
      '## [1.2.2] — 2026-01-02',
      '## [1.2.1] — 2026-01-01',
      '## [0.9.0] — 2025-01-01',
      '',
    ].join('\n'),
    'README.md': [
      '> **Status:** v1.2.3 is the current stable package line.',
      '',
      '| Version | Theme |',
      '|---|---|',
      '| `1.2.1` | First |',
      '| `1.2.2` | Second |',
      '| `1.2.3` | Third |',
      '',
      'pip install gavio==1.2.3',
      '',
    ].join('\n'),
    'spec/README.md': '| Schema | Describes |\n|---|---|\n| [`Alpha.schema.json`](./Alpha.schema.json) | A |\n',
    'spec/Alpha.schema.json': '{}\n',
    'docs/page.md': 'See [Alpha](../spec/Alpha.schema.json).\n',
  }
}

function fixture(mutate = () => {}) {
  const root = mkdtempSync(join(tmpdir(), 'gavio-docs-truth-'))
  const files = baseFiles()
  mutate(files)
  for (const [rel, text] of Object.entries(files)) {
    if (text === null) continue
    const file = join(root, rel)
    mkdirSync(dirname(file), { recursive: true })
    writeFileSync(file, text)
  }
  return root
}

function errorsOf(mutate) {
  return checkDocsTruth(fixture(mutate)).errors
}

function assertError(errors, fragment) {
  assert.ok(
    errors.some((error) => error.includes(fragment)),
    `expected an error containing ${JSON.stringify(fragment)}, got:\n${errors.join('\n')}`,
  )
}

test('a consistent repository passes', () => {
  assert.deepEqual(errorsOf(), [])
})

test('the real repository passes', () => {
  assert.deepEqual(checkDocsTruth(repoRoot).errors, [])
})

test('a stale README status line fails', () => {
  const errors = errorsOf((f) => {
    f['README.md'] = f['README.md'].replace('v1.2.3 is the current', 'v1.2.2 is the current')
  })
  assertError(errors, 'status line says v1.2.2, package is 1.2.3')
})

test('a missing status line fails', () => {
  assertError(errorsOf((f) => (f['README.md'] = f['README.md'].replace('Status', 'Note'))), 'status line')
})

test('a version map that stops before the package version fails', () => {
  const errors = errorsOf((f) => {
    f['README.md'] = f['README.md'].replace('| `1.2.3` | Third |\n', '')
  })
  assertError(errors, 'version map ends at 1.2.2, package is 1.2.3')
})

test('a released version missing from the middle of the map fails', () => {
  const errors = errorsOf((f) => {
    f['README.md'] = f['README.md'].replace('| `1.2.2` | Second |\n', '')
  })
  assertError(errors, 'version map is missing 1.2.2')
})

test('releases older than the first map row do not need a row', () => {
  assert.deepEqual(errorsOf(), [])
})

test('a map row without a CHANGELOG release fails', () => {
  const errors = errorsOf((f) => {
    f['README.md'] = f['README.md'].replace('| `1.2.3` | Third |', '| `1.2.3` | Third |\n| `1.2.4` | Fourth |')
  })
  assertError(errors, 'version map row 1.2.4 has no CHANGELOG release')
})

test('a map out of order fails', () => {
  const errors = errorsOf((f) => {
    f['README.md'] = f['README.md']
      .replace('| `1.2.1` | First |\n| `1.2.2` | Second |', '| `1.2.2` | Second |\n| `1.2.1` | First |')
  })
  assertError(errors, 'not in ascending order')
})

test('a package version with no CHANGELOG section fails', () => {
  const errors = errorsOf((f) => {
    f['CHANGELOG.md'] = f['CHANGELOG.md'].replace('## [1.2.3] — 2026-01-03\n', '')
  })
  assertError(errors, 'CHANGELOG.md has no released section "## [1.2.3]')
})

// The fixture page has one line already, so a one-line snippet lands on line 2; the pom's
// <artifactId> (where the match starts) is its third line.
for (const [name, text, line] of [
  ['pip pin', 'pip install gavio==1.2.2', 2],
  ['pip extra', 'pip install "gavio[yaml]>=1.2.2"', 2],
  ['npm pin', 'npm install gavio@1.2.2', 2],
  ['npm caret', '"gavio": "gavio@^1.2.2"', 2],
  ['gradle', 'implementation("io.github.manojmallick:gavio-core:1.2.2")', 2],
  ['gate call', 'python3 scripts/stable_release_gate.py --version 1.2.2', 2],
  [
    'pom',
    '<dependency>\n  <artifactId>gavio-interceptor-pii</artifactId>\n  <version>1.2.2</version>\n</dependency>',
    3,
  ],
]) {
  test(`a stale ${name} in the docs fails`, () => {
    const errors = errorsOf((f) => (f['docs/page.md'] = `${f['docs/page.md']}${text}\n`))
    assertError(errors, `docs/page.md:${line}: install/gate snippet pins 1.2.2, package is 1.2.3`)
  })
}

test('stale snippets are found in the docs-vp guide and package READMEs too', () => {
  const errors = errorsOf((f) => {
    f['docs-vp/guide/start.md'] = 'npm install gavio@1.0.0\n'
    f['packages/gavio-js/README.md'] = 'pip install gavio==1.0.0\n'
  })
  assertError(errors, 'docs-vp/guide/start.md:1')
  assertError(errors, 'packages/gavio-js/README.md:1')
})

test('a line marked docs-truth:ignore may quote an old version', () => {
  const errors = errorsOf((f) => {
    f['docs/page.md'] = 'Upgrading from 1.0: `pip install gavio==1.0.0` <!-- docs-truth:ignore -->\n'
  })
  assert.deepEqual(errors, [])
})

test('the CHANGELOG may quote any version', () => {
  const errors = errorsOf((f) => (f['CHANGELOG.md'] += '- pin `gavio==0.9.0` in your requirements\n'))
  assert.deepEqual(errors, [])
})

test('a schema on disk that spec/README.md does not list fails', () => {
  const errors = errorsOf((f) => (f['spec/Beta.schema.json'] = '{}\n'))
  assertError(errors, 'schema table does not list spec/Beta.schema.json')
})

test('a schema row without a file fails', () => {
  const errors = errorsOf((f) => {
    f['spec/README.md'] += '| [`Ghost.schema.json`](./Ghost.schema.json) | G |\n'
  })
  assertError(errors, 'lists Ghost.schema.json, which is not in spec/')
})

test('a doc that mentions a schema that does not exist fails', () => {
  const errors = errorsOf((f) => (f['docs/page.md'] += 'Planned: `Missing.schema.json`.\n'))
  assertError(errors, 'docs/page.md:2: mentions Missing.schema.json')
})

test('a schema that lives outside spec/ may be mentioned', () => {
  const errors = errorsOf((f) => {
    f['benchmarks/latest.schema.json'] = '{}\n'
    f['docs/page.md'] += 'Validated by latest.schema.json.\n'
  })
  assert.deepEqual(errors, [])
})

test('an old benchmark run is a note, not an error', () => {
  const root = fixture((f) => (f['benchmarks/latest.json'] = '{"gavioVersion": "1.0.0"}\n'))
  const { errors, notes } = checkDocsTruth(root)
  assert.deepEqual(errors, [])
  assert.equal(notes.length, 1)
  assert.match(notes[0], /measured Gavio 1\.0\.0; the package is 1\.2\.3/)
})
