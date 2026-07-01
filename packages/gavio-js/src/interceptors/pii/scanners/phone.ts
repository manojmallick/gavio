/** Phone number scanner — E.164 and common national formats. */

import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

// E.164 (+CC...) or national groupings with separators. A digit-count filter
// (7–15) below avoids matching short numbers / years.
const PHONE =
  /(?<![\w.])(?:\+?\d{1,3}[ .-]?)?(?:\(\d{1,4}\)[ .-]?)?\d{2,4}(?:[ .-]?\d{2,4}){2,4}(?![\w])/g

export interface PhoneScannerOptions {
  locales?: string[]
}

export function phoneScanner(options: PhoneScannerOptions = {}): PiiScanner {
  const locales = options.locales ?? ['NL', 'DE', 'GB', 'US']
  return {
    entityType: 'PHONE',
    tier: 1,
    confidence: 0.85,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(PHONE)) {
        const digitCount = (m[0].match(/\d/g) ?? []).length
        if (digitCount < 7 || digitCount > 15) continue
        const idx = ctx.nextIndex('PHONE')
        out.push(
          makeMatch({
            entityType: 'PHONE',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            confidence: 0.85,
            replacement: `[PHONE_${idx}]`,
          }),
        )
      }
      return out
    },
    supportsLocale(locale: string): boolean {
      return locales.includes(locale.toUpperCase())
    },
  }
}
