/** PiiScanner interface and ScannerRegistry. */

import type { ScanContext } from './context.js'
import type { PiiMatch } from './match.js'

/**
 * Detects one class of PII entity within text.
 *
 * Scanners are tiered: tier 1 = regex, tier 2 = NER/ML, tier 3 = LLM. Lower
 * tiers run first so cheap deterministic matches are found before expensive
 * ones. v0.1.0 ships only tier-1 regex scanners.
 */
export interface PiiScanner {
  /** e.g. 'EMAIL', 'IBAN', 'BSN'. */
  readonly entityType: string
  /** default: 1 */
  readonly tier?: 1 | 2 | 3
  scan(text: string, ctx: ScanContext): PiiMatch[] | Promise<PiiMatch[]>
  /** default: 1.0 */
  readonly confidence?: number
  supportsLanguage?(lang: string): boolean
  supportsLocale?(locale: string): boolean
}

export function scannerTier(s: PiiScanner): number {
  return s.tier ?? 1
}

/** Registry of scanners, discoverable by entity type at runtime. */
export class ScannerRegistry {
  private scanners: PiiScanner[] = []

  constructor(scanners?: PiiScanner[]) {
    for (const s of scanners ?? []) this.register(s)
  }

  register(scanner: PiiScanner): this {
    this.scanners.push(scanner)
    return this
  }

  /** Return scanners sorted by tier (lowest first). */
  all(): PiiScanner[] {
    return [...this.scanners].sort((a, b) => scannerTier(a) - scannerTier(b))
  }

  byEntityType(entityType: string): PiiScanner[] {
    return this.scanners.filter((s) => s.entityType === entityType)
  }

  get size(): number {
    return this.scanners.length
  }
}
