/** US ABA routing-number scanner — 9 digits + mod-10 checksum (F-SEC-01, FinTech pack). */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

const ROUTING = /\b\d{9}\b/g
const WEIGHTS = [3, 7, 1, 3, 7, 1, 3, 7, 1]

/** ABA checksum: weighted digit sum must be a non-zero multiple of 10. */
export function validRoutingNumber(candidate: string): boolean {
  if (candidate.length !== 9) return false
  let sum = 0
  for (let i = 0; i < 9; i++) sum += WEIGHTS[i]! * (candidate.charCodeAt(i) - 48)
  return sum > 0 && sum % 10 === 0
}

export function routingNumberScanner(): PiiScanner {
  return {
    entityType: 'ROUTING_NUMBER',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(ROUTING)) {
        if (!validRoutingNumber(m[0])) continue
        const idx = ctx.nextIndex('ROUTING_NUMBER')
        out.push(
          makeMatch({
            entityType: 'ROUTING_NUMBER',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            replacement: `[ROUTING_NUMBER_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
