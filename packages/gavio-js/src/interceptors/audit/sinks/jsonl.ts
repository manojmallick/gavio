/** jsonlSink — append-only JSON-lines audit store (F-DX-08, F-QUA-09). */

import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import type { AuditRecord } from '../record.js'
import type { AuditSink } from '../sink.js'

export interface JsonlSinkOptions {
  path: string
}

/**
 * Append each audit record as one JSON line. Zero runtime dependencies.
 *
 * Supports `purge(subjectId)` for right-to-erasure: matching lines are dropped
 * and the file is rewritten atomically (temp file + rename).
 */
export function jsonlSink(options: JsonlSinkOptions): AuditSink {
  const { path } = options
  mkdirSync(dirname(path), { recursive: true })
  return {
    async write(record: AuditRecord): Promise<void> {
      appendFileSync(path, JSON.stringify(record.toJSON()) + '\n', 'utf8')
    },
    async purge(subjectId: string): Promise<number> {
      if (!existsSync(path)) return 0
      const kept: string[] = []
      let removed = 0
      for (const line of readFileSync(path, 'utf8').split('\n')) {
        if (line.trim() === '') continue
        try {
          if ((JSON.parse(line) as { subjectId?: unknown }).subjectId === subjectId) {
            removed++
            continue
          }
        } catch {
          // preserve non-JSON lines untouched
        }
        kept.push(line)
      }
      if (removed > 0) {
        const tmp = path + '.tmp'
        writeFileSync(tmp, kept.map((l) => l + '\n').join(''), 'utf8')
        renameSync(tmp, path)
      }
      return removed
    },
  }
}
