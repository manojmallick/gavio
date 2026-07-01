/**
 * Secret / credential scanner (F-SEC-04).
 *
 * Detects API keys, tokens, JWTs, PEM private keys, and database connection
 * strings. These must never leave the device, so SecretScanner is tier 1 and
 * runs by default.
 */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

// [label, pattern] — ordered most-specific first. All patterns are global.
const PATTERNS: Array<[string, RegExp]> = [
  ['ANTHROPIC_KEY', /\bsk-ant-[A-Za-z0-9_-]{20,}\b/g],
  ['OPENAI_KEY', /\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b/g],
  ['AWS_ACCESS_KEY', /\b(?:AKIA|ASIA)[0-9A-Z]{16}\b/g],
  ['GITHUB_TOKEN', /\bgh[pousr]_[A-Za-z0-9]{36,}\b/g],
  ['JWT', /\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g],
  ['PRIVATE_KEY', /-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----/g],
  [
    'DB_CONNECTION_STRING',
    /\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis):\/\/[^\s"']+/g,
  ],
]

export function secretScanner(): PiiScanner {
  return {
    entityType: 'SECRET',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const [, pattern] of PATTERNS) {
        for (const m of text.matchAll(pattern)) {
          const idx = ctx.nextIndex('SECRET')
          out.push(
            makeMatch({
              entityType: 'SECRET',
              start: m.index,
              end: m.index + m[0].length,
              value: m[0],
              replacement: `[SECRET_${idx}]`,
            }),
          )
        }
      }
      return out
    },
  }
}
