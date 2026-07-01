/**
 * Stamps per-directory package.json markers after the dual build so Node treats
 * dist/esm as ESM and dist/cjs as CommonJS, regardless of the root package type.
 */

import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const distDir = fileURLToPath(new URL('../dist', import.meta.url))

for (const [sub, type] of [
  ['esm', 'module'],
  ['cjs', 'commonjs'],
]) {
  const dir = `${distDir}/${sub}`
  mkdirSync(dir, { recursive: true })
  writeFileSync(`${dir}/package.json`, JSON.stringify({ type }, null, 2) + '\n')
}

console.log('finalize-build: wrote dist/esm/package.json + dist/cjs/package.json')
