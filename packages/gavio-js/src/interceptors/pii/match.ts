/** PiiMatch — a single detected PII entity within a span of text. */

/**
 * One detected entity.
 *
 * `start`/`end` are half-open character offsets into the scanned text.
 * `replacement` is the placeholder used in REDACT mode; `value` is the
 * original text (never logged — used only for restore).
 */
export interface PiiMatch {
  entityType: string
  start: number
  end: number
  value: string
  confidence: number
  /** e.g. '[EMAIL_1]'. */
  replacement: string
}

export function matchLength(m: PiiMatch): number {
  return m.end - m.start
}

export function makeMatch(init: {
  entityType: string
  start: number
  end: number
  value: string
  confidence?: number
  replacement: string
}): PiiMatch {
  if (init.start < 0 || init.end < init.start) {
    throw new Error(`Invalid PiiMatch span: start=${init.start}, end=${init.end}`)
  }
  return {
    entityType: init.entityType,
    start: init.start,
    end: init.end,
    value: init.value,
    confidence: init.confidence ?? 1.0,
    replacement: init.replacement,
  }
}
