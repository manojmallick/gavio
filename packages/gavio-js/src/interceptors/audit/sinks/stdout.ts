/** stdoutSink — human-readable audit output for development (F-OBS-05). */

import type { AuditRecord } from '../record.js'
import type { AuditSink } from '../sink.js'

export interface StdoutSinkOptions {
  pretty?: boolean
  write?: (line: string) => void
}

/** Print each audit record to stdout. Zero dependencies. */
export function stdoutSink(options: StdoutSinkOptions = {}): AuditSink {
  const pretty = options.pretty ?? true
  // eslint-disable-next-line no-console
  const emit = options.write ?? ((line: string) => console.log(line))
  return {
    async write(record: AuditRecord): Promise<void> {
      const data = record.toJSON()
      emit(pretty ? formatPretty(data) : JSON.stringify(data))
    },
  }
}

function formatPretty(data: Record<string, unknown>): string {
  const usage = data['tokenUsage'] as { totalTokens: number }
  const piiTypes = data['piiEntityTypes'] as string[]
  const pii = piiTypes.length > 0 ? piiTypes : ['none']
  const interceptors = data['interceptorsFired'] as string[]
  const traceId = String(data['traceId'])
  const cost = Number(data['costUsd'])
  return (
    '[gavio:audit] ' +
    `trace=${traceId.slice(0, 18)}… ` +
    `${String(data['provider'])}/${String(data['model'])} ` +
    `tokens=${usage.totalTokens} ` +
    `cost=$${cost.toFixed(6)} ` +
    `latency=${String(data['latencyMs'])}ms ` +
    `cache=${data['cacheHit'] ? 'HIT' : 'miss'} ` +
    `pii=${pii.join(',')} ` +
    `interceptors=[${interceptors.join(',')}]`
  )
}
