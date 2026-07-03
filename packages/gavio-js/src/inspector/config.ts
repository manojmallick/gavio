/**
 * Inspector configuration (F-DX-09/F-DX-10) — capture mode, server binding,
 * and the safety gates that keep content capture opt-in.
 */

import { ConfigurationError } from '../errors.js'

/** How much of each request the inspector captures. */
export type InspectorMode = 'full' | 'redacted' | 'metadata'

const MODES: readonly InspectorMode[] = ['full', 'redacted', 'metadata']

export interface InspectorConfig {
  /** Master switch. Default: false — the inspector never runs unasked. */
  enabled?: boolean
  /** Capture mode. Default: 'full' in dev mode, 'metadata' otherwise. */
  mode?: InspectorMode
  /** HTTP port; 0 = ephemeral. Default: 7411. */
  port?: number
  /** Bind address. Default: '127.0.0.1' (loopback only). */
  bind?: string
  /** If set, every endpoint requires `Authorization: Bearer <token>`. */
  authToken?: string
  /** Ring-buffer capacity (traces). Default: 1000. */
  maxTraces?: number
  /** Required to run 'full' capture outside dev mode. Default: false. */
  unsafeContentCaptureAck?: boolean
  /** Start the local HTTP server. Default: true. */
  startServer?: boolean
}

/** InspectorConfig with every default applied and every gate validated. */
export interface ResolvedInspectorConfig {
  enabled: boolean
  mode: InspectorMode
  port: number
  bind: string
  authToken: string | null
  maxTraces: number
  unsafeContentCaptureAck: boolean
  startServer: boolean
}

export const DEFAULT_INSPECTOR_PORT = 7411
export const DEFAULT_MAX_TRACES = 1000

const LOOPBACK_BINDS = new Set(['127.0.0.1', 'localhost', '::1'])

function envPort(): number | undefined {
  const raw = process.env['GAVIO_INSPECT_PORT']
  if (raw === undefined || raw === '') return undefined
  const n = Number(raw)
  if (!Number.isInteger(n) || n < 0 || n > 65535) {
    throw new ConfigurationError(`GAVIO_INSPECT_PORT must be an integer 0-65535, got ${JSON.stringify(raw)}`)
  }
  return n
}

function envMode(): InspectorMode | undefined {
  const raw = process.env['GAVIO_INSPECT_MODE']
  if (raw === undefined || raw === '') return undefined
  if (!(MODES as string[]).includes(raw)) {
    throw new ConfigurationError(
      `GAVIO_INSPECT_MODE must be one of ${MODES.join(', ')}, got ${JSON.stringify(raw)}`,
    )
  }
  return raw as InspectorMode
}

/**
 * Apply defaults (explicit config > env var > built-in) and enforce the
 * safety gates. Throws {@link ConfigurationError} (a GavioError) when:
 *
 * - mode is 'full' outside dev mode without `unsafeContentCaptureAck`, or
 * - the server would bind a non-loopback address without an `authToken`.
 */
export function resolveInspectorConfig(
  config: InspectorConfig,
  devMode: boolean,
): ResolvedInspectorConfig {
  const mode = config.mode ?? envMode() ?? (devMode ? 'full' : 'metadata')
  if (!MODES.includes(mode)) {
    throw new ConfigurationError(`inspector mode must be one of ${MODES.join(', ')}, got ${JSON.stringify(mode)}`)
  }

  const resolved: ResolvedInspectorConfig = {
    enabled: config.enabled ?? false,
    mode,
    port: config.port ?? envPort() ?? DEFAULT_INSPECTOR_PORT,
    bind: config.bind ?? '127.0.0.1',
    authToken: config.authToken ?? null,
    maxTraces: config.maxTraces ?? DEFAULT_MAX_TRACES,
    unsafeContentCaptureAck: config.unsafeContentCaptureAck ?? false,
    startServer: config.startServer ?? true,
  }
  if (!resolved.enabled) return resolved

  if (resolved.mode === 'full' && !devMode && !resolved.unsafeContentCaptureAck) {
    throw new ConfigurationError(
      "inspector mode 'full' captures raw prompt and response content. Outside dev mode you must " +
        'opt in explicitly with { unsafeContentCaptureAck: true }, or use mode ' +
        "'redacted' / 'metadata' instead.",
    )
  }
  if (!LOOPBACK_BINDS.has(resolved.bind) && resolved.authToken === null) {
    throw new ConfigurationError(
      `inspector bind ${JSON.stringify(resolved.bind)} is not loopback — exposing trace data on the ` +
        'network requires an authToken. Set { authToken } or bind to 127.0.0.1.',
    )
  }
  return resolved
}

/** True when GAVIO_INSPECT=1|true asks for the inspector via the environment. */
export function envInspectEnabled(): boolean {
  const raw = process.env['GAVIO_INSPECT']
  return raw === '1' || raw === 'true'
}
