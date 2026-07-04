/** SWIFT/BIC scanner — context-gated (F-SEC-01, FinTech pack).
 *
 * Matches an 8- or 11-character BIC only when explicitly labelled `SWIFT`/`BIC`,
 * so ordinary 8-letter uppercase words never trigger a false positive.
 */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

// Keyword classes keep the label case-insensitive while requiring an UPPERCASE
// code (real BICs are uppercase). Group 1 is the code, at the end of the match.
const SWIFT =
  /\b(?:[Ss][Ww][Ii][Ff][Tt]|[Bb][Ii][Cc])(?:\s+[Cc]ode)?\s*[:#]?\s*([A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\b/g

export function swiftBicScanner(): PiiScanner {
  return {
    entityType: 'SWIFT_BIC',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(SWIFT)) {
        const code = m[1]!
        const end = m.index + m[0].length
        const start = end - code.length
        const idx = ctx.nextIndex('SWIFT_BIC')
        out.push(
          makeMatch({
            entityType: 'SWIFT_BIC',
            start,
            end,
            value: code,
            replacement: `[SWIFT_BIC_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
