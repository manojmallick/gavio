/** JSONL runtime exporter. */

import { appendFileSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'
import type { GavioRuntimeEvent, GavioRuntimeExporter } from './base.js'
import { metadataOnlyEvent } from './base.js'

export interface JsonlRuntimeExporterOptions {
  path?: string
  write?: (line: string) => void
  metadataOnly?: boolean
}

export function jsonlRuntimeExporter(
  options: JsonlRuntimeExporterOptions,
): GavioRuntimeExporter {
  if (options.path === undefined && options.write === undefined) {
    throw new Error('jsonlRuntimeExporter requires path or write')
  }
  if (options.path !== undefined && options.write !== undefined) {
    throw new Error('pass either path or write, not both')
  }
  if (options.path !== undefined) mkdirSync(dirname(options.path), { recursive: true })
  const metadataOnly = options.metadataOnly ?? true
  const write = options.write ?? ((line: string) => appendFileSync(options.path!, line, 'utf8'))

  return {
    exportEvent(event: GavioRuntimeEvent): void {
      const payload = metadataOnly ? metadataOnlyEvent(event) : event
      write(JSON.stringify(payload) + '\n')
    },
  }
}
