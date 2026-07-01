/** IBAN scanner — regex candidate + ISO 13616 mod-97 checksum validation. */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

// Candidate: 2 letters, 2 check digits, 11–30 alphanumerics (optionally spaced).
const IBAN = /\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]){11,30}\b/g

/** ISO 13616 mod-97: rearrange, convert letters to numbers, check %97 == 1. */
export function validIban(candidate: string): boolean {
  const cleaned = candidate.replace(/ /g, '').toUpperCase()
  if (cleaned.length < 15) return false
  const rearranged = cleaned.slice(4) + cleaned.slice(0, 4)
  let digits = ''
  for (const ch of rearranged) {
    if (ch >= 'A' && ch <= 'Z') {
      digits += (ch.charCodeAt(0) - 55).toString()
    } else if (ch >= '0' && ch <= '9') {
      digits += ch
    } else {
      return false
    }
  }
  return mod97(digits) === 1
}

/** Compute n % 97 over a large numeric string without BigInt overflow concerns. */
function mod97(numeric: string): number {
  let remainder = 0
  for (const ch of numeric) {
    remainder = (remainder * 10 + (ch.charCodeAt(0) - 48)) % 97
  }
  return remainder
}

export function ibanScanner(): PiiScanner {
  return {
    entityType: 'IBAN',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(IBAN)) {
        if (!validIban(m[0])) continue
        const idx = ctx.nextIndex('IBAN')
        out.push(
          makeMatch({
            entityType: 'IBAN',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            replacement: `[IBAN_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
