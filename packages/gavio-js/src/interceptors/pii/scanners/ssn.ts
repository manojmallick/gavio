/** US Social Security Number scanner. */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

// AAA-GG-SSSS with hyphens or spaces. Requires a separator to avoid colliding
// with bare 9-digit numbers (handled by the BSN scanner / others).
const SSN = /\b(?!000|666|9\d\d)\d{3}[ -](?!00)\d{2}[ -](?!0000)\d{4}\b/g

export function ssnScanner(): PiiScanner {
  return {
    entityType: 'SSN',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(SSN)) {
        const idx = ctx.nextIndex('SSN')
        out.push(
          makeMatch({
            entityType: 'SSN',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            replacement: `[SSN_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
