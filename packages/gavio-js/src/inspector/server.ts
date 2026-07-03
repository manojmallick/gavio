/**
 * InspectorServer — the local HTTP API + web UI (node:http only, zero deps).
 *
 * Endpoints (all GET):
 *   /              → vendored web UI (text/html)
 *   /api/health    → status, SDK version/kind, mode, trace + drop counters
 *   /api/pipeline  → provider/model/interceptor layout + ordering lints
 *   /api/traces    → trace summaries, chronological ascending (?limit=N)
 *   /api/traces/id → { summary, events } for one trace
 *   /api/stream    → Server-Sent Events, one `data:` line per bus event
 *
 * Every response carries `X-Gavio-Inspector-Mode`. If an authToken is
 * configured, every endpoint requires `Authorization: Bearer <token>`.
 */

import { createServer } from 'node:http'
import type { IncomingMessage, Server, ServerResponse } from 'node:http'
import { VERSION } from '../version.js'
import type { TraceBuffer } from './buffer.js'
import type { InspectorBus } from './bus.js'
import type { InspectorMode } from './config.js'
import { INSPECTOR_UI_HTML } from './ui.js'

/** Snapshot of the gateway's pipeline, served by /api/pipeline. */
export interface PipelineInfo {
  provider: string
  model: string
  devMode: boolean
  dryRun: boolean
  interceptors: Array<{ name: string }>
  lints: Array<{ level: 'warning'; message: string }>
}

/** Ordering lints over registered interceptor names (F-DX-10). */
export function pipelineLints(names: string[]): Array<{ level: 'warning'; message: string }> {
  const lints: Array<{ level: 'warning'; message: string }> = []
  const pii = names.indexOf('pii_guard')
  if (pii < 0) return lints
  const audit = names.indexOf('audit')
  if (audit >= 0 && audit < pii) {
    lints.push({
      level: 'warning',
      message: 'audit registered before pii_guard — audit will hash unredacted prompts',
    })
  }
  const cache = names.findIndex((n) => n.includes('cache'))
  if (cache >= 0 && cache < pii) {
    lints.push({
      level: 'warning',
      message: 'cache registered before pii_guard — raw PII used as cache key',
    })
  }
  return lints
}

export interface InspectorServerOptions {
  bus: InspectorBus
  buffer: TraceBuffer
  mode: InspectorMode
  port: number
  bind: string
  authToken: string | null
  pipeline: () => PipelineInfo
}

export class InspectorServer {
  private readonly bus: InspectorBus
  private readonly buffer: TraceBuffer
  private readonly mode: InspectorMode
  private readonly authToken: string | null
  private readonly pipeline: () => PipelineInfo
  private readonly server: Server
  private readonly listening: Promise<number>

  constructor(options: InspectorServerOptions) {
    this.bus = options.bus
    this.buffer = options.buffer
    this.mode = options.mode
    this.authToken = options.authToken
    this.pipeline = options.pipeline

    this.server = createServer((req, res) => this.handle(req, res))
    // Keep-alive sockets (SSE aside) must not hold the process open forever.
    this.server.unref()
    this.listening = new Promise<number>((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(options.port, options.bind, () => {
        const address = this.server.address()
        resolve(typeof address === 'object' && address !== null ? address.port : options.port)
      })
    })
  }

  /** Resolves with the actual port once the server is accepting connections. */
  ready(): Promise<number> {
    return this.listening
  }

  /** The bound port (0 until the server is listening). */
  get port(): number {
    const address = this.server.address()
    return typeof address === 'object' && address !== null ? address.port : 0
  }

  async close(): Promise<void> {
    await new Promise<void>((resolve) => {
      this.server.closeAllConnections?.()
      this.server.close(() => resolve())
    })
  }

  private handle(req: IncomingMessage, res: ServerResponse): void {
    res.setHeader('X-Gavio-Inspector-Mode', this.mode)

    if (this.authToken !== null) {
      const header = req.headers['authorization']
      if (header !== `Bearer ${this.authToken}`) {
        this.json(res, 401, { error: 'unauthorized' })
        return
      }
    }

    const url = new URL(req.url ?? '/', 'http://inspector.local')
    const path = url.pathname

    if (req.method !== 'GET') {
      this.json(res, 405, { error: 'method not allowed' })
      return
    }

    if (path === '/') {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
      res.end(INSPECTOR_UI_HTML)
      return
    }
    if (path === '/api/health') {
      this.json(res, 200, {
        status: 'ok',
        version: VERSION,
        mode: this.mode,
        sdk: 'js',
        traces: this.buffer.size,
        drops: this.bus.drops,
      })
      return
    }
    if (path === '/api/pipeline') {
      this.json(res, 200, this.pipeline())
      return
    }
    if (path === '/api/traces') {
      const rawLimit = url.searchParams.get('limit')
      const limit = rawLimit !== null && Number.isInteger(Number(rawLimit))
        ? Number(rawLimit)
        : undefined
      this.json(res, 200, { traces: this.buffer.list(limit) })
      return
    }
    if (path.startsWith('/api/traces/')) {
      const record = this.buffer.get(path.slice('/api/traces/'.length))
      if (record === undefined) {
        this.json(res, 404, { error: 'not found' })
        return
      }
      this.json(res, 200, record)
      return
    }
    if (path === '/api/stream') {
      this.stream(req, res)
      return
    }
    this.json(res, 404, { error: 'not found' })
  }

  private stream(req: IncomingMessage, res: ServerResponse): void {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    res.flushHeaders()

    const unsubscribe = this.bus.subscribe((event) => {
      res.write(`data: ${JSON.stringify(event)}\n\n`)
    })
    req.on('close', unsubscribe)
    res.on('close', unsubscribe)
  }

  private json(res: ServerResponse, status: number, body: unknown): void {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
    res.end(JSON.stringify(body))
  }
}
