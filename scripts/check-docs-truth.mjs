#!/usr/bin/env node
// F-DOC-10: docs must not state facts the repository can compute.
//
//   node scripts/check-docs-truth.mjs            fail when a doc disagrees with its source
//
// Checked against their sources:
//   * the package version (packages/gavio-py/pyproject.toml): the README status line, the
//     README version map, every install snippet and `stable_release_gate.py --version` call
//     in the README, the docs trees and the package READMEs;
//   * CHANGELOG.md: the version map has a row for every release from its first row on;
//   * spec/*.schema.json: spec/README.md lists exactly the schemas on disk, and no doc links a
//     schema file that does not exist.
//
// Benchmark numbers are generated, not checked here: see scripts/gen-benchmark-results.mjs.
// A line that quotes an old version on purpose (a migration note, say) can carry
// `docs-truth:ignore` anywhere on it. Only files git knows (tracked, or new and not ignored) are
// read, so local-only plan files cannot fail the check.

import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

const SEMVER = '\\d+\\.\\d+\\.\\d+'
const IGNORE = 'docs-truth:ignore'
const SKIP_DIRS = new Set(['node_modules', '.git', 'dist', 'target', '.venv', '.vitepress'])

// Docs that may carry install snippets. CHANGELOG.md is history and deliberately absent.
const SNIPPET_ROOTS = ['README.md', 'STABILITY.md', 'docs', 'docs-vp/guide', 'docs-vp/index.md', 'examples/README.md']

// pip `gavio==X`, `gavio[extra]>=X`; npm `gavio@X`, `gavio@^X`; Maven/Gradle `gavio-core:X`.
const SNIPPET_PATTERNS = [
  new RegExp(`\\bgavio(?:\\[[a-z,]+\\])?(?:==|>=|@[\\^~]?)(${SEMVER})`, 'g'),
  new RegExp(`\\bgavio-[a-z-]+:(${SEMVER})`, 'g'),
  new RegExp(`stable_release_gate\\.py --version (${SEMVER})`, 'g'),
  new RegExp(`<artifactId>gavio-[a-z-]+</artifactId>\\s*<version>(${SEMVER})</version>`, 'g'),
]

function read(root, rel) {
  return readFileSync(join(root, rel), 'utf8')
}

function walk(root, rel, out) {
  for (const entry of readdirSync(join(root, rel), { withFileTypes: true })) {
    if (SKIP_DIRS.has(entry.name)) continue
    const child = rel === '' ? entry.name : `${rel}/${entry.name}`
    if (entry.isDirectory()) walk(root, child, out)
    else out.push(child)
  }
  return out
}

/** Files git would show (tracked, or untracked and not ignored); a plain walk without git. */
function listFiles(root) {
  if (existsSync(join(root, '.git'))) {
    try {
      const out = execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], {
        cwd: root,
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024,
      })
      return out.split('\0').filter((file) => file !== '' && existsSync(join(root, file)))
    } catch {
      // fall through to the walk
    }
  }
  return walk(root, '', [])
}

function under(files, prefix, suffix) {
  const dir = prefix.endsWith('/') ? prefix : `${prefix}/`
  return files.filter((file) => (file === prefix || file.startsWith(dir)) && file.endsWith(suffix))
}

function compareVersions(a, b) {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < 3; i += 1) if (pa[i] !== pb[i]) return pa[i] - pb[i]
  return 0
}

function lineOf(text, index) {
  return text.slice(0, index).split('\n').length
}

function lineAt(text, index) {
  const start = text.lastIndexOf('\n', index - 1) + 1
  const end = text.indexOf('\n', index)
  return text.slice(start, end === -1 ? text.length : end)
}

function packageVersion(root) {
  const match = /^version\s*=\s*"(\d+\.\d+\.\d+)"/m.exec(read(root, 'packages/gavio-py/pyproject.toml'))
  if (match === null) throw new Error('no version in packages/gavio-py/pyproject.toml')
  return match[1]
}

function releasedVersions(root) {
  const headings = read(root, 'CHANGELOG.md').matchAll(
    new RegExp(`^## \\[(${SEMVER})\\] — \\d{4}-\\d{2}-\\d{2}`, 'gm'),
  )
  return [...headings].map((m) => m[1])
}

function checkStatusAndMap(root, version, errors) {
  const readme = read(root, 'README.md')
  const released = releasedVersions(root)

  if (!released.includes(version)) {
    errors.push(`CHANGELOG.md has no released section "## [${version}] — YYYY-MM-DD"`)
  }

  const status = /\*\*Status:\*\* v(\d+\.\d+\.\d+) is the current stable package line/.exec(readme)
  if (status === null) {
    errors.push('README.md: status line "**Status:** vX.Y.Z is the current stable package line" not found')
  } else if (status[1] !== version) {
    errors.push(`README.md:${lineOf(readme, status.index)}: status line says v${status[1]}, package is ${version}`)
  }

  const rows = [...readme.matchAll(new RegExp(`^\\| \`(${SEMVER})\` \\|`, 'gm'))].map((m) => m[1])
  if (rows.length === 0) {
    errors.push('README.md: version map has no rows')
    return
  }
  for (let i = 1; i < rows.length; i += 1) {
    if (compareVersions(rows[i - 1], rows[i]) >= 0) {
      errors.push(`README.md: version map is not in ascending order at ${rows[i - 1]} -> ${rows[i]}`)
    }
  }
  if (rows[rows.length - 1] !== version) {
    errors.push(`README.md: version map ends at ${rows[rows.length - 1]}, package is ${version}`)
  }
  for (const row of rows) {
    if (!released.includes(row)) errors.push(`README.md: version map row ${row} has no CHANGELOG release`)
  }
  const mapped = new Set(rows)
  for (const release of released) {
    if (compareVersions(release, rows[0]) >= 0 && !mapped.has(release)) {
      errors.push(`README.md: version map is missing ${release}, released in CHANGELOG.md`)
    }
  }
}

function checkSnippets(root, files, version, errors) {
  const targets = new Set()
  for (const base of SNIPPET_ROOTS) for (const file of under(files, base, '.md')) targets.add(file)
  for (const file of files) if (/^packages\/[^/]+\/README\.md$/.test(file)) targets.add(file)

  for (const file of [...targets].sort()) {
    const text = read(root, file)
    for (const pattern of SNIPPET_PATTERNS) {
      for (const match of text.matchAll(pattern)) {
        if (match[1] === version) continue
        if (lineAt(text, match.index).includes(IGNORE)) continue
        errors.push(`${file}:${lineOf(text, match.index)}: install/gate snippet pins ${match[1]}, package is ${version}`)
      }
    }
  }
}

function checkSchemas(root, files, errors) {
  const schemas = files.filter((file) => file.endsWith('.schema.json'))
  const known = new Set(schemas.map((file) => file.slice(file.lastIndexOf('/') + 1)))
  const onDisk = schemas.filter((file) => file.startsWith('spec/')).map((file) => file.slice('spec/'.length))

  const specReadme = read(root, 'spec/README.md')
  const listed = new Set(
    [...specReadme.matchAll(/^\| \[`([A-Za-z0-9]+\.schema\.json)`\]\(\.\/[^)]+\)/gm)].map((m) => m[1]),
  )
  for (const name of onDisk.sort()) {
    if (!listed.has(name)) errors.push(`spec/README.md: schema table does not list spec/${name}`)
  }
  for (const name of [...listed].sort()) {
    if (!onDisk.includes(name)) errors.push(`spec/README.md: schema table lists ${name}, which is not in spec/`)
  }

  const docs = new Set(['README.md', 'spec/README.md', 'test-vectors/README.md'])
  for (const base of ['docs', 'docs-vp/guide']) for (const file of under(files, base, '.md')) docs.add(file)
  for (const file of [...docs].sort()) {
    if (!files.includes(file)) continue
    const text = read(root, file)
    for (const match of text.matchAll(/\b([A-Za-z][A-Za-z0-9]*\.schema\.json)\b/g)) {
      if (known.has(match[1]) || lineAt(text, match.index).includes(IGNORE)) continue
      errors.push(`${file}:${lineOf(text, match.index)}: mentions ${match[1]}, which does not exist`)
    }
  }
}

/**
 * Check the docs of `root` against their sources.
 * @returns {{ errors: string[], notes: string[], version: string }} errors fail the check,
 *   notes are advice.
 */
export function checkDocsTruth(root = defaultRoot) {
  const errors = []
  const notes = []
  const files = listFiles(root)
  const version = packageVersion(root)
  checkStatusAndMap(root, version, errors)
  checkSnippets(root, files, version, errors)
  checkSchemas(root, files, errors)

  if (files.includes('benchmarks/latest.json')) {
    const measured = JSON.parse(read(root, 'benchmarks/latest.json')).gavioVersion
    if (measured !== version) {
      notes.push(
        `benchmarks/latest.json measured Gavio ${measured}; the package is ${version}. ` +
          'Rerun the chain-overhead benchmark to refresh it.',
      )
    }
  }
  return { errors, notes, version }
}

function main() {
  const { errors, notes, version } = checkDocsTruth(defaultRoot)
  for (const note of notes) console.log(`note: ${note}`)
  if (errors.length > 0) {
    console.error(`${errors.length} doc fact(s) disagree with the repository:`)
    for (const error of errors) console.error(`  - ${error}`)
    process.exit(1)
  }
  console.log(`docs agree with the repository (version ${version}).`)
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main()
}
