/**
 * InspectorServer — the local HTTP API + web UI (node:http only, zero deps).
 *
 * Endpoints (GET unless noted):
 *   /                      → vendored web UI (text/html)
 *   /api/health            → status, SDK version/kind, mode, trace + drop counters
 *   /api/pipeline          → provider/model/interceptor layout + ordering lints
 *   /api/traces            → trace summaries, ascending (?limit=N&q=<id/hash prefix>)
 *   /api/traces/id         → { summary, events } for one trace
 *   /api/traces/id/export  → trace as a test case (?format=..., F-DX-12)
 *   /api/dag               → agent call graph (?root= | ?session_id=, F-OBS-10)
 *   /api/sessions          → per-session rollups (F-OBS-10)
 *   /api/stats             → RED aggregates (?group_by=&since=, F-DX-08)
 *   /api/simulate-cost     → re-cost one trace under another model
 *   /api/chain/verify      → 400 on the live server (store mode is Python-only)
 *   /api/replay (POST)     → re-fire a trace through the live gateway (F-DX-11)
 *   /api/stream            → Server-Sent Events, one `data:` line per bus event
 *
 * Every response carries `X-Gavio-Inspector-Mode`. If an authToken is
 * configured, every endpoint requires `Authorization: Bearer <token>`.
 */

import { createServer } from 'node:http'
import type { IncomingMessage, Server, ServerResponse } from 'node:http'
import type { PricingProvider } from '../pricing.js'
import { TokenUsage } from '../types.js'
import type { Message } from '../types.js'
import { VERSION } from '../version.js'
import { buildDag, buildSessions, buildStats } from './analytics.js'
import type { TraceBuffer, TraceRecord } from './buffer.js'
import type { InspectorBus } from './bus.js'
import type { InspectorMode } from './config.js'
import { EXPORT_FORMATS, exportTrace } from './export.js'
import { INSPECTOR_UI_HTML } from './ui.js'

/**
 * Re-fires a captured request through the live gateway (F-DX-11). Wired by the
 * Gateway to its own `complete()` so the full interceptor chain always runs.
 */
export type ReplayHandler = (opts: {
  messages: Message[]
  model?: string
  metadata?: Record<string, unknown>
  options?: Record<string, unknown>
}) => Promise<{ traceId: string }>

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
  /** Used by /api/simulate-cost. */
  pricing: PricingProvider
  /** Late-bound accessor — the Gateway attaches its handler after construction. */
  replayHandler?: () => ReplayHandler | null
}

export class InspectorServer {
  private readonly bus: InspectorBus
  private readonly buffer: TraceBuffer
  private readonly mode: InspectorMode
  private readonly authToken: string | null
  private readonly pipeline: () => PipelineInfo
  private readonly pricing: PricingProvider
  private readonly replayHandler: () => ReplayHandler | null
  private readonly server: Server
  private readonly listening: Promise<number>

  constructor(options: InspectorServerOptions) {
    this.bus = options.bus
    this.buffer = options.buffer
    this.mode = options.mode
    this.authToken = options.authToken
    this.pipeline = options.pipeline
    this.pricing = options.pricing
    this.replayHandler = options.replayHandler ?? (() => null)

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
    const path = url.pathname.replace(/\/+$/, '') || '/'

    if (req.method === 'POST') {
      if (path === '/api/replay') {
        void this.replay(req, res)
        return
      }
      this.json(res, 404, { error: 'not found' })
      return
    }
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
      this.traces(res, url.searchParams)
      return
    }
    if (path === '/api/dag') {
      this.dag(res, url.searchParams)
      return
    }
    if (path === '/api/sessions') {
      this.json(res, 200, { sessions: buildSessions(this.buffer.list()) })
      return
    }
    if (path === '/api/stats') {
      this.stats(res, url.searchParams)
      return
    }
    if (path === '/api/simulate-cost') {
      this.simulateCost(res, url.searchParams)
      return
    }
    if (path === '/api/chain/verify') {
      this.json(res, 400, {
        error:
          'chain verification requires an audit store; run: gavio inspect --store <audit.jsonl>',
      })
      return
    }
    if (path.startsWith('/api/traces/') && path.endsWith('/export')) {
      const traceId = path.slice('/api/traces/'.length, -'/export'.length)
      void this.export(res, traceId, url.searchParams)
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

  private traces(res: ServerResponse, params: URLSearchParams): void {
    const rawLimit = params.get('limit')
    const limit = rawLimit !== null && Number.isInteger(Number(rawLimit))
      ? Number(rawLimit)
      : undefined
    let summaries = this.buffer.list(limit)
    const q = params.get('q')
    if (q !== null && q !== '') {
      summaries = summaries.filter((s) =>
        [s.traceId, s.promptHash, s.responseHash].some(
          (value) => typeof value === 'string' && value.startsWith(q),
        ),
      )
    }
    this.json(res, 200, { traces: summaries })
  }

  private dag(res: ServerResponse, params: URLSearchParams): void {
    const root = params.get('root')
    const sessionId = params.get('session_id')
    if (root === null && sessionId === null) {
      this.json(res, 400, { error: 'pass ?root=<trace_id> or ?session_id=<id>' })
      return
    }
    const dag = buildDag(this.buffer.list(), root ?? undefined, sessionId ?? undefined)
    if (dag === null) {
      this.json(res, 404, { error: 'not found' })
      return
    }
    this.json(res, 200, dag)
  }

  private stats(res: ServerResponse, params: URLSearchParams): void {
    let stats
    try {
      stats = buildStats(
        this.buffer.list(),
        params.get('group_by') ?? undefined,
        params.get('since') ?? undefined,
      )
    } catch (error) {
      this.json(res, 400, { error: error instanceof Error ? error.message : String(error) })
      return
    }
    this.json(res, 200, stats)
  }

  private simulateCost(res: ServerResponse, params: URLSearchParams): void {
    const traceId = params.get('trace_id')
    const model = params.get('model')
    if (!traceId || !model) {
      this.json(res, 400, { error: 'pass ?trace_id=<id>&model=<model>' })
      return
    }
    const record = this.buffer.get(traceId)
    if (record === undefined) {
      this.json(res, 404, { error: 'not found' })
      return
    }
    const usage = record.summary.usage
    if (usage === undefined) {
      this.json(res, 400, { error: 'trace has no token usage' })
      return
    }
    const simulated = this.pricing.estimate(
      model,
      new TokenUsage(usage.promptTokens ?? 0, usage.completionTokens ?? 0),
    )
    const original = record.summary.costUsd ?? 0
    this.json(res, 200, {
      traceId,
      model: record.summary.model,
      costUsd: original,
      simulatedModel: model,
      simulatedCostUsd: simulated,
      deltaUsd: round8(simulated - original),
      usage,
    })
  }

  private async export(
    res: ServerResponse,
    traceId: string,
    params: URLSearchParams,
  ): Promise<void> {
    if (this.mode === 'metadata') {
      this.json(res, 403, { error: 'export requires full or redacted capture mode' })
      return
    }
    const format = params.get('format')
    if (format === null || !(EXPORT_FORMATS as readonly string[]).includes(format)) {
      this.json(res, 400, {
        error: `format must be one of [${EXPORT_FORMATS.map((f) => `'${f}'`).join(', ')}]`,
      })
      return
    }
    const record = this.buffer.get(traceId)
    if (record === undefined) {
      this.json(res, 404, { error: 'not found' })
      return
    }
    try {
      const { contentType, body } = await exportTrace(record, format)
      res.writeHead(200, { 'Content-Type': contentType })
      res.end(body)
    } catch (error) {
      this.json(res, 400, { error: error instanceof Error ? error.message : String(error) })
    }
  }

  private async replay(req: IncomingMessage, res: ServerResponse): Promise<void> {
    if (this.mode !== 'full') {
      this.json(res, 403, { error: 'replay requires full capture mode' })
      return
    }
    const handler = this.replayHandler()
    if (handler === null) {
      this.json(res, 403, { error: 'no live gateway attached; replay unavailable' })
      return
    }
    let body: Record<string, unknown>
    try {
      const raw = await readBody(req)
      const parsed: unknown = raw === '' ? {} : JSON.parse(raw)
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('not an object')
      }
      body = parsed as Record<string, unknown>
    } catch {
      this.json(res, 400, { error: 'invalid JSON body' })
      return
    }
    const traceId = body['traceId']
    if (typeof traceId !== 'string' || traceId === '') {
      this.json(res, 400, { error: 'body must include traceId' })
      return
    }
    const record: TraceRecord | undefined = this.buffer.get(traceId)
    if (record === undefined) {
      this.json(res, 404, { error: 'not found' })
      return
    }
    const overrides = (body['overrides'] ?? {}) as Record<string, unknown>
    let messages = overrides['messages'] as Message[] | undefined
    if (messages === undefined) {
      const start = record.events.find((e) => e.type === 'trace.start')
      messages = start?.data['messages'] as Message[] | undefined
    }
    if (messages === undefined || messages.length === 0) {
      this.json(res, 400, { error: 'trace has no captured messages to replay' })
      return
    }
    const model = (overrides['model'] as string | undefined) ?? record.summary.model ?? undefined
    const options = (overrides['options'] as Record<string, unknown> | undefined) ?? {}
    try {
      // The replayed call runs the full interceptor chain — PII guard
      // included, never bypassed.
      const response = await handler({
        messages,
        model,
        metadata: { replay_of: traceId },
        options,
      })
      this.json(res, 200, { traceId: response.traceId, replayOf: traceId })
    } catch (error) {
      const name = error instanceof Error ? error.constructor.name : typeof error
      const message = error instanceof Error ? error.message : String(error)
      this.json(res, 502, { error: `${name}: ${message}`, replayOf: traceId })
    }
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

function round8(value: number): number {
  return Math.round(value * 1e8) / 1e8
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    req.on('data', (chunk: Buffer) => chunks.push(chunk))
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}
