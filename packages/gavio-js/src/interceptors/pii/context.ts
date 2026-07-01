/** ScanContext — per-request state shared across PII scanners. */

/**
 * Context threaded through every scanner for one request.
 *
 * Tracks a monotonic per-entity-type index so repeated entities get stable,
 * distinct placeholders (`[EMAIL_1]`, `[EMAIL_2]`).
 */
export class ScanContext {
  readonly language: string
  readonly locale: string
  private counters: Record<string, number> = {}

  constructor(language = 'en', locale = 'NL') {
    this.language = language
    this.locale = locale
  }

  /** Return the next 1-based index for an entity type. */
  nextIndex(entityType: string): number {
    this.counters[entityType] = (this.counters[entityType] ?? 0) + 1
    return this.counters[entityType]
  }
}
