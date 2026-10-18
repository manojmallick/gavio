#!/usr/bin/env node
// Runs the offline examples listed in examples/matrix.json against the SDKs in
// THIS repository instead of the published packages, so unreleased code is
// testable before a release is cut.
//
//   node scripts/run-examples.mjs                       # every SDK, every example
//   node scripts/run-examples.mjs --sdk javascript      # one SDK
//   node scripts/run-examples.mjs --sdk python --example 01,25
//   node scripts/run-examples.mjs --list                # show what would run
//
// How the override works (the examples themselves are never edited):
//   python      editable install of packages/gavio-py into a throwaway venv
//   javascript  `npm pack` of packages/gavio-js, installed with
//               `npm install --no-save --no-package-lock <tarball>` in a copy
//   java        `mvn install -DskipTests` of the whole packages/gavio-java
//               reactor into an isolated local repository (-Dmaven.repo.local);
//               each example then resolves io.github.manojmallick:gavio-* from it
//
// Each example runs from a throwaway copy so generated files (workflow-release
// .json, node_modules, target/) never dirty the checkout.

import { spawnSync } from 'node:child_process'
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  symlinkSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SDKS = ['python', 'javascript', 'java']

function main() {
  const options = parseArgs(process.argv.slice(2))
  const matrix = JSON.parse(readFileSync(join(repoRoot, 'examples/matrix.json'), 'utf8'))
  const selected = selectExamples(matrix, options)

  if (options.list) {
    for (const { sdk, example, entry } of selected) {
      const note = entry.skipRun ? `  (skipped: ${entry.skipRun})` : ''
      console.log(`${sdk.padEnd(10)} ${example.id} ${entry.path}${note}`)
    }
    process.exit(0)
  }

  const work = options.workDir ?? mkdtempSync(join(tmpdir(), 'gavio-examples-'))
  mkdirSync(work, { recursive: true })
  const failures = []
  let ran = 0

  try {
    for (const sdk of options.sdks) {
      const jobs = selected.filter((job) => job.sdk === sdk && !job.entry.skipRun)
      if (jobs.length === 0) continue
      heading(`${sdk}: preparing the in-repo SDK`)
      const runner = prepare[sdk](work, options)
      for (const { example, entry } of jobs) {
        heading(`${sdk}: example ${example.id} ${example.title}`)
        const dir = stage(work, sdk, entry.path)
        const ok = runner(dir, entry)
        ran += 1
        if (!ok) failures.push(`${sdk} ${example.id} ${example.title}`)
      }
    }
  } finally {
    if (!options.keep && options.workDir === undefined) rmSync(work, { recursive: true, force: true })
  }

  if (ran === 0) {
    console.error('No examples matched.')
    process.exit(1)
  }
  if (failures.length > 0) {
    console.error(`\n${failures.length} example(s) failed against the in-repo SDKs:`)
    for (const failure of failures) console.error(`  - ${failure}`)
    process.exit(1)
  }
  console.log(`\n${ran} example(s) passed against the in-repo SDKs.`)
}

// ---- per-SDK preparation ----------------------------------------------------

const prepare = {
  python(workDir, opts) {
    const venv = join(workDir, 'venv')
    const python = join(venv, 'bin', 'python')
    if (!existsSync(python)) {
      must('python venv', run(opts.python, ['-m', 'venv', venv]))
      must('pip upgrade', run(python, ['-m', 'pip', 'install', '--quiet', '--upgrade', 'pip']))
    }
    must('pip install -e packages/gavio-py',
      run(python, ['-m', 'pip', 'install', '--quiet', '-e', join(repoRoot, 'packages/gavio-py')]))
    const probe = spawnSync(python, ['-c', 'import gavio, sys; sys.stdout.write(gavio.__file__)'], {
      encoding: 'utf8',
    })
    const location = probe.stdout.trim()
    if (!location.startsWith(join(repoRoot, 'packages/gavio-py'))) {
      fail(`python resolved gavio from ${location || 'nowhere'}, not packages/gavio-py`)
    }
    console.log(`gavio (python) -> ${location}`)
    return (dir, entry) => {
      const scripts = entry.entry ?? [onlyFile(dir, '.py')]
      return scripts.every((script) =>
        run(python, [script], { cwd: dir, env: { PYTHONDONTWRITEBYTECODE: '1' } }, script))
    }
  },

  javascript(workDir, opts) {
    const sdkDir = join(repoRoot, 'packages/gavio-js')
    if (!opts.skipBuild) {
      must('npm ci (packages/gavio-js)', run('npm', ['ci', '--no-audit', '--no-fund'], { cwd: sdkDir }))
      must('npm run build (packages/gavio-js)', run('npm', ['run', 'build'], { cwd: sdkDir }))
    }
    const packDir = join(workDir, 'npm-pack')
    mkdirSync(packDir, { recursive: true })
    const pack = spawnSync('npm', ['pack', '--silent', '--pack-destination', packDir], {
      cwd: sdkDir,
      encoding: 'utf8',
    })
    if (pack.status !== 0) fail(`npm pack failed:\n${pack.stderr}`)
    const tarball = join(packDir, pack.stdout.trim().split('\n').pop())
    console.log(`gavio (javascript) -> ${tarball}`)
    return (dir, entry) => {
      rmSync(join(dir, 'package-lock.json'), { force: true })
      const packageJson = join(dir, 'package.json')
      const installArgs = ['install', '--no-save', '--no-package-lock', '--no-audit', '--no-fund', tarball]
      if (!existsSync(packageJson)) {
        // Examples without a manifest (24) only need the SDK itself.
        mustTrue('npm init', run('npm', ['init', '-y', '--silent'], { cwd: dir }))
      }
      if (!run('npm', installArgs, { cwd: dir }, 'npm install <local tarball>')) return false
      const installed = JSON.parse(readFileSync(join(dir, 'node_modules/gavio/package.json'), 'utf8'))
      const sdkVersion = JSON.parse(readFileSync(join(sdkDir, 'package.json'), 'utf8')).version
      if (installed.version !== sdkVersion) {
        console.error(`installed gavio ${installed.version}, expected in-repo ${sdkVersion}`)
        return false
      }
      const scripts = entry.entry
      if (scripts !== undefined) {
        return scripts.every((script) => run('node', [script], { cwd: dir }, script))
      }
      if (existsSync(packageJson) && JSON.parse(readFileSync(packageJson, 'utf8')).scripts?.start) {
        return run('npm', ['start', '--silent'], { cwd: dir }, 'npm start')
      }
      return run('node', [onlyFile(dir, '.mjs')], { cwd: dir })
    }
  },

  java(workDir, opts) {
    const reactor = join(repoRoot, 'packages/gavio-java')
    const m2 = opts.m2 ?? join(workDir, 'm2')
    const mvn = [`-Dmaven.repo.local=${m2}`, '-B', '-q']
    must('mvn install -DskipTests (packages/gavio-java)',
      run('mvn', [...mvn, 'install', '-DskipTests', '-Dmaven.javadoc.skip=true', '-Dgpg.skip=true'], { cwd: reactor }))
    const core = join(m2, 'io/github/manojmallick/gavio-core')
    console.log(`gavio (java) -> ${core} (${existsSync(core) ? readdirSync(core).join(', ') : 'missing'})`)
    if (!existsSync(core)) fail('mvn install did not produce gavio-core in the isolated repository')
    return (dir) => run('mvn', [...mvn, 'compile', 'exec:java'], { cwd: dir }, 'mvn compile exec:java')
  },
}

// ---- helpers ------------------------------------------------------------------

function stage(workDir, sdk, relPath) {
  // Mirror the repo layout so examples that reach into sibling directories keep working.
  const root = join(workDir, 'stage', sdk)
  const dest = join(root, relPath)
  if (!existsSync(dest)) {
    mkdirSync(dirname(dest), { recursive: true })
    cpSync(join(repoRoot, relPath), dest, {
      recursive: true,
      filter: (src) => !/(^|\/)(node_modules|target|__pycache__)(\/|$)/.test(src),
    })
    // `apps/` (example 24 starts the control plane) and `policy-packs/` (the SDKs
    // look the catalog up from the working directory upwards) live at the repo root.
    for (const shared of ['apps', 'policy-packs']) {
      const link = join(root, shared)
      if (!existsSync(link)) symlinkSync(join(repoRoot, shared), link)
    }
  }
  return dest
}

function onlyFile(dir, extension) {
  const files = readdirSync(dir).filter((name) => name.endsWith(extension))
  if (files.length !== 1) {
    fail(`${dir}: expected exactly one ${extension} entry file (or an "entry" list in the matrix), found ${files.length}`)
  }
  return files[0]
}

function run(command, args, spawnOptions = {}, label = `${command} ${args.join(' ')}`) {
  // Some examples (05 Inspector) wait for Enter before exiting; feed them one.
  const result = spawnSync(command, args, {
    stdio: ['pipe', 'inherit', 'inherit'],
    input: '\n',
    timeout: 10 * 60 * 1000,
    ...spawnOptions,
    env: { ...process.env, ...spawnOptions.env },
  })
  if (result.status !== 0) {
    console.error(`FAILED (${result.status ?? result.signal ?? result.error}): ${label}`)
    return false
  }
  return true
}

function must(label, ok) {
  if (!ok) fail(`${label} failed`)
}

function mustTrue(label, ok) {
  must(label, ok)
}

function fail(message) {
  console.error(message)
  process.exit(1)
}

function heading(text) {
  console.log(`\n== ${text}`)
}

function selectExamples(matrix, opts) {
  const jobs = []
  for (const example of matrix.examples) {
    if (opts.examples !== undefined && !opts.examples.includes(example.id)) continue
    for (const sdk of opts.sdks) {
      const entry = example[sdk]
      if (entry?.path !== undefined) jobs.push({ sdk, example, entry })
    }
  }
  return jobs
}

function parseArgs(argv) {
  const opts = {
    sdks: SDKS,
    examples: undefined,
    list: false,
    keep: false,
    skipBuild: false,
    python: process.env.PYTHON ?? 'python3',
    workDir: undefined,
    m2: undefined,
  }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    const next = () => {
      i += 1
      if (argv[i] === undefined) fail(`${arg} needs a value`)
      return argv[i]
    }
    if (arg === '--sdk') {
      const value = next()
      if (value !== 'all') {
        if (!SDKS.includes(value)) fail(`--sdk must be one of ${SDKS.join(', ')}, all`)
        opts.sdks = [value]
      }
    } else if (arg === '--example') opts.examples = next().split(',').map((id) => id.trim().padStart(2, '0'))
    else if (arg === '--list') opts.list = true
    else if (arg === '--keep') opts.keep = true
    else if (arg === '--skip-build') opts.skipBuild = true
    else if (arg === '--python') opts.python = next()
    else if (arg === '--work-dir') opts.workDir = resolve(next())
    else if (arg === '--m2') opts.m2 = resolve(next())
    else fail(`unknown argument: ${arg}`)
  }
  return opts
}

main()
