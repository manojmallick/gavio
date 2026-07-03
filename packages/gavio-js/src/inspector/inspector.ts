/**
 * Inspector (F-DX-09/F-DX-10) — wires the event bus, the ring buffer, and
 * (optionally) the local HTTP server. One instance per Gateway; created only
 * when inspection is explicitly enabled.
 */

import { PricingProvider } from '../pricing.js'
import type { GavioRequest } from '../request.js'
import { TraceBuffer } from './buffer.js'
import { InspectorBus } from './bus.js'
import type { ResolvedInspectorConfig } from './config.js'
import { TraceEmitter } from './emitter.js'
import { InspectorServer } from './server.js'
import type { PipelineInfo, ReplayHandler } from './server.js'

export interface InspectorOptions {
  /** Used by /api/simulate-cost; the Gateway passes its PricingProvider. */
  pricing?: PricingProvider
}

export class Inspector {
  readonly config: ResolvedInspectorConfig
  readonly bus: InspectorBus
  readonly buffer: TraceBuffer
  readonly server: InspectorServer | null
  /** Used by /api/simulate-cost; the Gateway passes its PricingProvider. */
  readonly pricing: PricingProvider
  /** Wired by the Gateway: /api/replay re-fires through the live pipeline. */
  replayHandler: ReplayHandler | null = null

  constructor(
    config: ResolvedInspectorConfig,
    pipeline: () => PipelineInfo,
    options: InspectorOptions = {},
  ) {
    this.config = config
    this.bus = new InspectorBus()
    this.buffer = new TraceBuffer({ maxTraces: config.maxTraces })
    this.bus.subscribe((event) => this.buffer.handle(event))
    this.pricing = options.pricing ?? new PricingProvider()

    this.server = config.startServer
      ? new InspectorServer({
          bus: this.bus,
          buffer: this.buffer,
          mode: config.mode,
          port: config.port,
          bind: config.bind,
          authToken: config.authToken,
          pipeline,
          pricing: this.pricing,
          replayHandler: () => this.replayHandler,
        })
      : null
  }

  /** Resolves with the actual HTTP port (undefined when startServer=false). */
  async ready(): Promise<number | undefined> {
    return this.server?.ready()
  }

  /** The bound HTTP port; 0 when no server is running or not yet listening. */
  get port(): number {
    return this.server?.port ?? 0
  }

  /** Create the per-trace emitter the gateway/chain emit through. */
  beginTrace(request: GavioRequest): TraceEmitter {
    return new TraceEmitter(this.bus, this.config.mode, request.traceId)
  }

  async close(): Promise<void> {
    await this.server?.close()
  }
}
