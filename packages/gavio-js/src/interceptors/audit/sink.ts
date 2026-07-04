/** AuditSink — the extensible destination for audit records. */

import type { AuditRecord } from './record.js'

/** Where audit records go. Implement `write` to add a backend. */
export interface AuditSink {
  write(record: AuditRecord): Promise<void>
  /**
   * Erase records for a data subject (GDPR Art. 17, F-QUA-09). Remove every
   * persisted record whose `subjectId` matches and resolve to the number
   * removed. Optional — non-persistent sinks (e.g. stdout) omit it.
   */
  purge?(subjectId: string): Promise<number>
  /** Flush/close any resources. Optional. */
  close?(): Promise<void>
}
