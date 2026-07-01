/** Dutch BSN scanner — regex + 11-proef (eleven-test) checksum. */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

// BSN is 8 or 9 digits; we validate the 9-digit form with the 11-proef.
const BSN = /\b\d{9}\b/g

/** 11-proef: sum of digit*weight (9,8,...,2,-1) must be divisible by 11. */
export function validBsn(digits: string): boolean {
  if (digits.length !== 9) return false
  const weights = [9, 8, 7, 6, 5, 4, 3, 2, -1]
  let total = 0
  for (let i = 0; i < 9; i++) {
    total += Number(digits[i]) * weights[i]!
  }
  return total % 11 === 0
}

export function bsnScanner(): PiiScanner {
  return {
    entityType: 'BSN',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(BSN)) {
        if (!validBsn(m[0])) continue
        const idx = ctx.nextIndex('BSN')
        out.push(
          makeMatch({
            entityType: 'BSN',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            replacement: `[BSN_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
