/** Runtime exporter contracts for Gavio runtime events. */

import type { InspectorEvent } from '../inspector/events.js'

const CONTENT_KEYS = new Set(['messages', 'content', 'diff'])

export type GavioRuntimeEvent = InspectorEvent

export interface GavioRuntimeExporter {
  /** Export one InspectorEvent/Gavio runtime event. Called on the request path. */
  exportEvent(event: GavioRuntimeEvent): void
  flush?(): void
  close?(): void
}

/**
 * Return a deep copy with content-bearing fields removed.
 *
 * JSONL runtime export defaults to this metadata-only privacy boundary, even
 * when the local Inspector is running in full or redacted capture mode.
 */
export function metadataOnlyEvent(event: GavioRuntimeEvent): GavioRuntimeEvent {
  const copy = structuredClone(event) as GavioRuntimeEvent
  stripContent(copy.data)
  return copy
}

function stripContent(value: unknown): void {
  if (Array.isArray(value)) {
    for (const item of value) stripContent(item)
    return
  }
  if (value === null || typeof value !== 'object') return
  const record = value as Record<string, unknown>
  for (const key of Object.keys(record)) {
    if (CONTENT_KEYS.has(key)) {
      delete record[key]
      continue
    }
    stripContent(record[key])
  }
}
