/**
 * Root run resolution (F-REL-09, shared with the planned F-AGT-01 task records).
 *
 * A *root run* is every provider call that belongs to one agent task. Each call is counted
 * under a root run id, resolved per request in this order:
 *
 * 1. `metadata.runId` (or `metadata.run_id`) when it is a non-empty string;
 * 2. else the root of the `parentTraceId` chain;
 * 3. else the request's own `traceId`.
 *
 * The chain root comes from a bounded least-recently-used map of `traceId -> root run id`.
 * Pure: no clock, no I/O. Behaviour is pinned by `test-vectors/loop-guard/decisions.json`
 * (`rootRunCases`) and described in `spec/README.md` ('Loop guard'). Port of `gavio.root_run`.
 */

import { ConfigurationError } from './errors.js'

export const DEFAULT_MAX_ENTRIES = 10_000

/**
 * The run id a caller set in request metadata, or null. Blank and non-string values are
 * ignored, so a stray `runId: 42` cannot merge runs.
 */
export function explicitRunId(metadata: Record<string, unknown> | null | undefined): string | null {
  if (!metadata) return null
  for (const key of ['runId', 'run_id']) {
    const value = metadata[key]
    if (typeof value === 'string' && value !== '') return value
  }
  return null
}

/**
 * Resolves a request to its root run id, remembering the chain in a bounded LRU map.
 *
 * A `parentTraceId` the map does not know — evicted, or from another process — is taken for
 * the root itself, so pass `metadata.runId` when a run spans processes or outlives the map.
 */
export class RootRunResolver {
  private readonly roots = new Map<string, string>() // insertion order = recency, oldest first
  private readonly maxEntries: number

  constructor(maxEntries: number = DEFAULT_MAX_ENTRIES) {
    if (!Number.isInteger(maxEntries) || maxEntries < 1) {
      throw new ConfigurationError('maxEntries must be an integer of at least 1')
    }
    this.maxEntries = maxEntries
  }

  /** The root run id of this call; also records `traceId` so children resolve to it. */
  resolve(
    traceId: string,
    parentTraceId?: string | null,
    metadata?: Record<string, unknown> | null,
  ): string {
    const explicit = explicitRunId(metadata)
    let root: string
    if (explicit !== null) {
      root = explicit
    } else if (parentTraceId) {
      const known = this.roots.get(parentTraceId)
      if (known !== undefined) {
        this.roots.delete(parentTraceId) // reading a parent refreshes it
        this.roots.set(parentTraceId, known)
      }
      root = known ?? parentTraceId
    } else {
      root = traceId
    }
    this.roots.delete(traceId)
    this.roots.set(traceId, root)
    while (this.roots.size > this.maxEntries) {
      const oldest = this.roots.keys().next().value as string
      this.roots.delete(oldest)
    }
    return root
  }

  get size(): number {
    return this.roots.size
  }
}
