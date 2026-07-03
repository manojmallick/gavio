/**
 * Re-vendors the shared inspector UI into src/inspector/ui.ts as a string
 * constant (JSON-stringified — never hand-escaped). Run after editing
 * //inspector-ui/index.html:
 *
 *     node scripts/vendor-inspector-ui.mjs
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const source = fileURLToPath(new URL('../../../inspector-ui/index.html', import.meta.url))
const target = fileURLToPath(new URL('../src/inspector/ui.ts', import.meta.url))

const html = readFileSync(source, 'utf8')

const out = `// Generated from inspector-ui/index.html — edit there and re-vendor.
// Regenerate with: node scripts/vendor-inspector-ui.mjs

/** The vendored inspector web UI, served at GET / by the inspector server. */
export const INSPECTOR_UI_HTML: string = ${JSON.stringify(html)}
`

writeFileSync(target, out)
console.log(`vendor-inspector-ui: wrote ${target} (${html.length} chars of HTML)`)
