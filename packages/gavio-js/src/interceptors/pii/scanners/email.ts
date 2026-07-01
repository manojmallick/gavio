/** Email address scanner (RFC 5322 pragmatic subset). */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g

export function emailScanner(): PiiScanner {
  return {
    entityType: 'EMAIL',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(EMAIL)) {
        const idx = ctx.nextIndex('EMAIL')
        out.push(
          makeMatch({
            entityType: 'EMAIL',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            replacement: `[EMAIL_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
