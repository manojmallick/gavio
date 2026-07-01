/** Audit-chain verification (F-OBS-02) and multi-agent DAG trace (F-OBS-03). */

import type { AuditRecord } from './record.js'

/**
 * Return true if the records form an intact hash chain. Each record's
 * previousHash must equal the content hash of the record before it; the first
 * must be empty. Any edit, reorder, or deletion breaks the chain.
 */
export function verifyChain(records: AuditRecord[]): boolean {
  let prevHash = ''
  for (const rec of records) {
    if (rec.previousHash !== prevHash) return false
    prevHash = rec.contentHash()
  }
  return true
}

export interface TraceNode {
  traceId: string
  agentId: string | null
  parentTraceId: string | null
  children: TraceNode[]
}

/**
 * Reconstruct the multi-agent DAG from audit records using parentTraceId +
 * traceId. Returns the root nodes (those with no known parent).
 */
export function buildCallGraph(records: AuditRecord[]): TraceNode[] {
  const nodes = new Map<string, TraceNode>()
  for (const rec of records) {
    nodes.set(rec.traceId, {
      traceId: rec.traceId,
      agentId: rec.agentId,
      parentTraceId: rec.parentTraceId,
      children: [],
    })
  }
  const roots: TraceNode[] = []
  for (const node of nodes.values()) {
    const parent = node.parentTraceId ? nodes.get(node.parentTraceId) : undefined
    if (parent) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}
