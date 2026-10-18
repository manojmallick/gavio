#!/usr/bin/env node
// Validates examples/matrix.json (the example parity source of truth) against
// the repository: listed paths exist, every example directory is listed, gaps
// are explicit (`planned` / `n/a` with a reason), and the example tables in
// README.md and examples/README.md agree with the matrix.
//
//   node scripts/check-example-matrix.mjs            # print the parity summary
//   node scripts/check-example-matrix.mjs --check    # exit 1 on any problem
//   node scripts/check-example-matrix.mjs --root DIR # check another checkout (tests)

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, posix, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const matrixPath = 'examples/matrix.json'
const readmeTables = [
  { file: 'README.md', label: 'README.md' },
  { file: 'examples/README.md', label: 'examples/README.md' },
]
const sdkDirs = { python: 'python', javascript: 'javascript', java: 'java' }
const sdkLinkLabels = { python: 'py', javascript: 'js', java: 'java' }
const gapStatuses = new Set(['planned', 'n/a'])
const needsKeyValues = new Set(['no', 'optional', 'yes'])

export function checkExampleMatrix(root) {
  const errors = []
  const fail = (message) => errors.push(message)

  const matrixFile = join(root, matrixPath)
  if (!existsSync(matrixFile)) {
    return { errors: [`${matrixPath} is missing`], matrix: null }
  }
  let matrix
  try {
    matrix = JSON.parse(readFileSync(matrixFile, 'utf8'))
  } catch (error) {
    return { errors: [`${matrixPath} is not valid JSON: ${error.message}`], matrix: null }
  }

  const sdks = Array.isArray(matrix.sdks) ? matrix.sdks : []
  if (JSON.stringify(sdks) !== JSON.stringify(Object.keys(sdkDirs))) {
    fail(`${matrixPath}: sdks must be exactly ${JSON.stringify(Object.keys(sdkDirs))}`)
  }
  const examples = Array.isArray(matrix.examples) ? matrix.examples : []
  if (examples.length === 0) fail(`${matrixPath}: examples must be a non-empty array`)

  const seenIds = new Set()
  const listedPaths = new Set()
  let previousId = ''
  for (const example of examples) {
    const id = String(example.id ?? '')
    if (!/^\d{2}$/.test(id)) {
      fail(`${matrixPath}: example id ${JSON.stringify(example.id)} must be two digits`)
      continue
    }
    if (seenIds.has(id)) fail(`${matrixPath}: duplicate example id ${id}`)
    seenIds.add(id)
    if (id < previousId) fail(`${matrixPath}: example ${id} is out of order`)
    previousId = id
    if (typeof example.title !== 'string' || example.title.trim() === '') {
      fail(`example ${id}: title is required`)
    }
    if (!needsKeyValues.has(example.needsKey)) {
      fail(`example ${id}: needsKey must be one of ${[...needsKeyValues].join(', ')}`)
    }

    for (const sdk of Object.keys(sdkDirs)) {
      const entry = example[sdk]
      const where = `example ${id} (${sdk})`
      if (entry === undefined || entry === null || typeof entry !== 'object') {
        fail(`${where}: gap is not listed - add { "path": ... } or { "status": "planned"|"n/a", "reason": ... }`)
        continue
      }
      if (entry.path !== undefined) {
        if (entry.status !== undefined) fail(`${where}: has both path and status`)
        if (typeof entry.path !== 'string' || entry.path === '') {
          fail(`${where}: path must be a non-empty string`)
          continue
        }
        listedPaths.add(entry.path)
        const dir = join(root, entry.path)
        if (!existsSync(dir) || !statSync(dir).isDirectory()) {
          fail(`${where}: listed path does not exist: ${entry.path}`)
          continue
        }
        if (!existsSync(join(dir, 'README.md'))) fail(`${where}: ${entry.path} has no README.md`)
        for (const rel of entry.entry ?? []) {
          if (!existsSync(join(dir, rel))) fail(`${where}: entry file does not exist: ${entry.path}/${rel}`)
        }
        if (entry.cliOnly !== undefined && (!Array.isArray(entry.cliOnly) || entry.cliOnly.length === 0)) {
          fail(`${where}: cliOnly must be a non-empty array of CLI steps`)
        }
        if (entry.skipRun !== undefined && (typeof entry.skipRun !== 'string' || entry.skipRun === '')) {
          fail(`${where}: skipRun must be a reason string`)
        }
      } else if (gapStatuses.has(entry.status)) {
        if (typeof entry.reason !== 'string' || entry.reason.trim() === '') {
          fail(`${where}: ${entry.status} entries need a reason`)
        }
        const prefix = `${id}-`
        const dirOnDisk = join(root, 'examples', sdkDirs[sdk])
        if (existsSync(dirOnDisk)) {
          for (const name of readdirSync(dirOnDisk)) {
            if (name.startsWith(prefix)) {
              fail(`${where}: listed as ${entry.status} but examples/${sdkDirs[sdk]}/${name} exists - list its path`)
            }
          }
        }
      } else {
        fail(`${where}: needs a path, or a status of planned/n/a (got ${JSON.stringify(entry.status)})`)
      }
    }
  }

  // Every example directory on disk must be listed.
  for (const sdk of Object.keys(sdkDirs)) {
    const dir = join(root, 'examples', sdkDirs[sdk])
    if (!existsSync(dir)) continue
    for (const name of readdirSync(dir)) {
      if (!/^\d{2}-/.test(name)) continue
      if (!statSync(join(dir, name)).isDirectory()) continue
      const rel = `examples/${sdkDirs[sdk]}/${name}`
      if (!listedPaths.has(rel)) fail(`${rel} exists but is not listed in ${matrixPath}`)
    }
  }

  for (const table of readmeTables) {
    checkReadmeTable(root, table, examples, fail)
  }

  return { errors, matrix }
}

function checkReadmeTable(root, { file, label }, examples, fail) {
  const full = join(root, file)
  if (!existsSync(full)) {
    fail(`${label}: file is missing`)
    return
  }
  const rows = parseExampleRows(readFileSync(full, 'utf8'), label, fail)
  const byId = new Map(rows.map((row) => [row.id, row]))
  const matrixIds = examples.map((example) => String(example.id))

  for (const id of byId.keys()) {
    if (!matrixIds.includes(id)) fail(`${label}: example ${id} is in the table but not in ${matrixPath}`)
  }
  const orderedRows = rows.map((row) => row.id).filter((id) => matrixIds.includes(id))
  const orderedMatrix = matrixIds.filter((id) => byId.has(id))
  if (JSON.stringify(orderedRows) !== JSON.stringify(orderedMatrix)) {
    fail(`${label}: example rows are not in matrix order`)
  }

  for (const example of examples) {
    const id = String(example.id)
    const row = byId.get(id)
    if (row === undefined) {
      fail(`${label}: example ${id} is in ${matrixPath} but missing from the table`)
      continue
    }
    if (row.needsKey !== example.needsKey) {
      fail(`${label}: example ${id} "Needs a key?" is ${JSON.stringify(row.needsKey)}, matrix says ${JSON.stringify(example.needsKey)}`)
    }
    for (const sdk of Object.keys(sdkDirs)) {
      const entry = example[sdk]
      if (entry === undefined || entry === null) continue
      const cell = row.cells[sdk]
      if (entry.path !== undefined) {
        const expectedHref = entry.path
        if (cell.kind !== 'link') {
          fail(`${label}: example ${id} ${sdk} should link ${expectedHref} but the table has ${cell.raw}`)
        } else {
          if (cell.label !== sdkLinkLabels[sdk]) {
            fail(`${label}: example ${id} ${sdk} link text is "${cell.label}", expected "${sdkLinkLabels[sdk]}"`)
          }
          const resolved = posix.normalize(posix.join(posix.dirname(file), cell.href)).replace(/\/$/, '')
          if (resolved !== expectedHref) {
            fail(`${label}: example ${id} ${sdk} links ${resolved}, matrix says ${expectedHref}`)
          }
        }
      } else if (gapStatuses.has(entry.status) && cell.kind !== 'gap') {
        fail(`${label}: example ${id} ${sdk} is ${entry.status} in the matrix but the table has ${cell.raw}`)
      }
    }
  }
}

function parseExampleRows(text, label, fail) {
  const rows = []
  const lines = text.split('\n')
  lines.forEach((line, index) => {
    const match = /^\|\s*(\d{2})\s*\|/.exec(line)
    if (match === null) return
    const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((cell) => cell.trim())
    if (cells.length !== 6) {
      fail(`${label}:${index + 1}: example row must have 6 columns (id, scenario, py, js, java, key), found ${cells.length}`)
      return
    }
    rows.push({
      id: match[1],
      needsKey: cells[5],
      cells: {
        python: parseLanguageCell(cells[2]),
        javascript: parseLanguageCell(cells[3]),
        java: parseLanguageCell(cells[4]),
      },
    })
  })
  return rows
}

function parseLanguageCell(raw) {
  if (/^[—–-]+$/.test(raw)) return { kind: 'gap', raw }
  const match = /^\[([^\]]+)\]\(([^)]+)\)$/.exec(raw)
  if (match === null) return { kind: 'other', raw }
  return { kind: 'link', raw, label: match[1], href: match[2] }
}

function summarize(matrix) {
  const counts = Object.fromEntries(Object.keys(sdkDirs).map((sdk) => [sdk, { available: 0, planned: 0, 'n/a': 0 }]))
  for (const example of matrix.examples) {
    for (const sdk of Object.keys(sdkDirs)) {
      const entry = example[sdk]
      if (entry?.path !== undefined) counts[sdk].available += 1
      else if (gapStatuses.has(entry?.status)) counts[sdk][entry.status] += 1
    }
  }
  const lines = [`Example parity (${matrix.examples.length} examples)`]
  for (const sdk of Object.keys(sdkDirs)) {
    const { available, planned } = counts[sdk]
    lines.push(`  ${sdk.padEnd(10)} available ${available}, planned ${planned}, n/a ${counts[sdk]['n/a']}`)
  }
  const gaps = []
  for (const example of matrix.examples) {
    for (const sdk of Object.keys(sdkDirs)) {
      const entry = example[sdk]
      if (gapStatuses.has(entry?.status)) gaps.push(`  ${example.id} ${sdk}: ${entry.status}`)
    }
  }
  if (gaps.length > 0) lines.push('Gaps:', ...gaps)
  return lines.join('\n')
}

function main(argv) {
  const rootIndex = argv.indexOf('--root')
  const root = rootIndex >= 0
    ? resolve(argv[rootIndex + 1])
    : resolve(dirname(fileURLToPath(import.meta.url)), '..')
  const { errors, matrix } = checkExampleMatrix(root)
  if (errors.length > 0) {
    console.error(`${matrixPath} check failed:`)
    for (const error of errors) console.error(`  - ${error}`)
    process.exit(1)
  }
  if (argv.includes('--check')) {
    console.log(`${matrixPath} is consistent with the repository and README tables.`)
  } else {
    console.log(summarize(matrix))
  }
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2))
}
