/** Credit card scanner — regex candidate + Luhn checksum validation. */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

// 13–19 digits, optionally separated by single spaces or hyphens.
const CARD = /\b(?:\d[ -]?){12,18}\d\b/g

export function luhnValid(number: string): boolean {
  const digits: number[] = []
  for (const c of number) {
    if (c >= '0' && c <= '9') digits.push(c.charCodeAt(0) - 48)
  }
  if (digits.length < 13 || digits.length > 19) return false
  let checksum = 0
  const parity = digits.length % 2
  for (let i = 0; i < digits.length; i++) {
    let d = digits[i]!
    if (i % 2 === parity) {
      d *= 2
      if (d > 9) d -= 9
    }
    checksum += d
  }
  return checksum % 10 === 0
}

export function creditCardScanner(): PiiScanner {
  return {
    entityType: 'CREDIT_CARD',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(CARD)) {
        if (!luhnValid(m[0])) continue
        const idx = ctx.nextIndex('CREDIT_CARD')
        out.push(
          makeMatch({
            entityType: 'CREDIT_CARD',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            replacement: `[CREDIT_CARD_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
