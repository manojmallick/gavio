/** AuditSink — the extensible destination for audit records. */

import type { AuditRecord } from './record.js'

/** Where audit records go. Implement `write` to add a backend. */
export interface AuditSink {
  write(record: AuditRecord): Promise<void>
  /** Flush/close any resources. Optional. */
  close?(): Promise<void>
}
