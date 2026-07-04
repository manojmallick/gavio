/** Gateway — the entry point. Wires interceptors around a provider adapter. */

import { InterceptorContext } from './context.js'
import { ConfigurationError, ProviderError } from './errors.js'
import { auditInterceptor, isAuditInterceptor } from './interceptors/audit/index.js'
import { isExecutorPolicy } from './interceptors/base.js'
import type { Executor, ExecutorPolicy, Interceptor } from './interceptors/base.js'
import { InterceptorChain } from './interceptors/chain.js'
import { envInspectEnabled, resolveInspectorConfig } from './inspector/config.js'
import type { InspectorConfig } from './inspector/config.js'
import { Inspector } from './inspector/inspector.js'
import { pipelineLints } from './inspector/server.js'
import type { PipelineInfo } from './inspector/server.js'
import { StreamBuffer } from './interceptors/reliability/stream-buffer.js'
import { PricingProvider } from './pricing.js'
import { buildAdapter } from './providers/index.js'
import type { ProviderAdapter } from './providers/base.js'
import { mockProvider } from './providers/mock.js'
import { GavioRequest } from './request.js'
import type { GavioResponse } from './response.js'
import { Provider, coerceProvider } from './types.js'
import type { Message, PromptLineage, PromptLineageInit } from './types.js'

export interface GatewayOptions {
  provider?: Provider | string
  model?: string
  adapter?: ProviderAdapter
  devMode?: boolean
  dryRun?: boolean
  pricing?: PricingProvider
  /**
   * Gavio Inspector (F-DX-09). Off by default — dev mode does NOT auto-enable
   * it. `true` enables with defaults; pass an {@link InspectorConfig} to tune
   * mode/port/etc. `GAVIO_INSPECT=1` enables it via the environment (with
   * `GAVIO_INSPECT_PORT` / `GAVIO_INSPECT_MODE` as defaults).
   */
  inspect?: boolean | InspectorConfig
}

export interface CompleteOptions {
  messages: Message[]
  model?: string
  agentId?: string | null
  parentTraceId?: string | null
  sessionId?: string | null
  metadata?: Record<string, unknown>
  /** Binary image inputs, scanned for PII before the provider call (F-SEC-09). */
  images?: Uint8Array[]
  /** Provider sampling options (temperature, maxTokens, etc.). */
  options?: Record<string, unknown>
  /** Prompt provenance (F-OBS-04): template, variables, and RAG chunk sources. */
  lineage?: PromptLineage | PromptLineageInit | null
}

export interface EmbedOptions {
  /** Input texts — one embedding vector is returned per text. */
  texts: string[]
  model?: string
  agentId?: string | null
  parentTraceId?: string | null
  sessionId?: string | null
  metadata?: Record<string, unknown>
  /** Provider embedding options (dimensions, encoding format, etc.). */
  options?: Record<string, unknown>
}

const DEFAULT_MODELS: Record<string, string> = {
  openai: 'gpt-4o',
  anthropic: 'claude-sonnet-4-6',
  mock: 'mock',
}

/**
 * Routes a request through the interceptor pipeline to a provider.
 *
 * Construct with `new Gateway({ provider, model })` then chain `.use(...)` and
 * `.withAdapter(...)`. A single instance is safe to reuse — per-request state
 * lives in an {@link InterceptorContext} created fresh for every call.
 */
export class Gateway {
  private readonly providerHint: Provider | undefined
  private modelHint: string | undefined
  private adapterOverride: ProviderAdapter | undefined
  private readonly devMode: boolean
  private readonly dryRunMode: boolean
  private readonly pricing: PricingProvider
  private readonly interceptors: Interceptor[] = []
  private readonly inspectorInstance: Inspector | null

  constructor(options: GatewayOptions = {}) {
    this.providerHint = options.provider ? coerceProvider(options.provider) : undefined
    this.modelHint = options.model
    this.adapterOverride = options.adapter
    this.devMode = options.devMode ?? false
    this.dryRunMode = options.dryRun ?? false
    this.pricing = options.pricing ?? new PricingProvider()
    this.inspectorInstance = this.buildInspector(options.inspect)
    if (this.inspectorInstance !== null) {
      // /api/replay re-fires through this gateway's full pipeline (F-DX-11).
      this.inspectorInstance.replayHandler = (opts) => this.complete(opts)
    }
  }

  /**
   * Resolve the `inspect` option (or GAVIO_INSPECT=1) into an Inspector.
   * Strictly opt-in: with no option and no env var this returns null and the
   * request path is completely untouched.
   */
  private buildInspector(inspect: boolean | InspectorConfig | undefined): Inspector | null {
    const option: boolean | InspectorConfig | undefined =
      inspect ?? (envInspectEnabled() ? true : undefined)
    if (option === undefined || option === false) return null
    const overrides: InspectorConfig = typeof option === 'object' ? option : {}
    const resolved = resolveInspectorConfig(
      { ...overrides, enabled: overrides.enabled ?? true },
      this.devMode,
    )
    if (!resolved.enabled) return null
    return new Inspector(resolved, () => this.pipelineInfo(), { pricing: this.pricing })
  }

  /** The Inspector attached to this gateway, or null when disabled. */
  get inspector(): Inspector | null {
    return this.inspectorInstance
  }

  /** Pipeline snapshot for the inspector's /api/pipeline endpoint (F-DX-10). */
  private pipelineInfo(): PipelineInfo {
    let provider: string
    let model: string
    try {
      const adapter = this.resolveAdapter()
      provider = adapter.providerName
      model = this.modelHint ?? this.resolveModel(adapter)
    } catch {
      provider = this.providerHint ?? 'unconfigured'
      model = this.modelHint ?? ''
    }
    const names = this.interceptors.map((i) => i.name)
    return {
      provider,
      model,
      devMode: this.devMode,
      dryRun: this.dryRunMode,
      interceptors: names.map((name) => ({ name })),
      lints: pipelineLints(names),
    }
  }

  /**
   * Build a Gateway from a config object or a JSON file path (F-DX-05).
   * Async so the config module loads lazily (avoids a circular import).
   */
  static async fromConfig(config: string | Record<string, unknown>): Promise<Gateway> {
    const mod = await import('./config.js')
    const data = typeof config === 'string' ? mod.loadConfig(config) : config
    return mod.buildFromConfig(data)
  }

  /** Register an interceptor or executor policy. First-registered = outermost. */
  use(interceptor: Interceptor): this {
    this.interceptors.push(interceptor)
    return this
  }

  /** Supply a provider adapter explicitly (overrides `provider`). */
  withAdapter(adapter: ProviderAdapter): this {
    this.adapterOverride = adapter
    return this
  }

  get model(): string {
    return this.modelHint ?? this.resolveModel(this.resolveAdapter())
  }

  get providerName(): string {
    return this.resolveAdapter().providerName
  }

  async complete(opts: CompleteOptions): Promise<GavioResponse> {
    const adapter = this.resolveAdapter()
    const model = opts.model ?? this.modelHint ?? this.resolveModel(adapter)

    const request = new GavioRequest({
      messages: opts.messages,
      model,
      provider: coerceProvider(adapter.providerName),
      agentId: opts.agentId ?? null,
      parentTraceId: opts.parentTraceId ?? null,
      sessionId: opts.sessionId ?? null,
      options: opts.options ?? {},
      metadata: opts.metadata ?? {},
      images: opts.images ?? [],
      lineage: opts.lineage ?? null,
    })
    const ctx = new InterceptorContext({
      traceId: request.traceId,
      agentId: request.agentId,
      parentTraceId: request.parentTraceId,
      sessionId: request.sessionId,
      dryRun: this.dryRunMode,
    })

    const { chain, executor } = this.buildPipeline(adapter, ctx)
    return this.executeTraced(request, ctx, chain, executor)
  }

  /**
   * Embed texts through the same interceptor pipeline as completions (F-SEC-10).
   *
   * Every input runs the full pre-interceptor chain — PII guard included —
   * before the provider's embedding API is called, and the post chain (audit,
   * metrics) runs on the way out. The response carries one vector per input in
   * {@link GavioResponse.embeddings} and empty `content`.
   */
  async embed(opts: EmbedOptions): Promise<GavioResponse> {
    const adapter = this.resolveAdapter()
    if (adapter.embed === undefined) {
      throw new ProviderError(`${adapter.providerName} does not support embeddings`)
    }
    const model = opts.model ?? this.modelHint ?? this.resolveModel(adapter)

    const request = new GavioRequest({
      messages: opts.texts.map((text) => ({ role: 'user', content: text })),
      model,
      provider: coerceProvider(adapter.providerName),
      agentId: opts.agentId ?? null,
      parentTraceId: opts.parentTraceId ?? null,
      sessionId: opts.sessionId ?? null,
      options: opts.options ?? {},
      metadata: { ...(opts.metadata ?? {}), call_type: 'embedding' },
    })
    const ctx = new InterceptorContext({
      traceId: request.traceId,
      agentId: request.agentId,
      parentTraceId: request.parentTraceId,
      sessionId: request.sessionId,
      dryRun: this.dryRunMode,
    })

    const { chain, executor } = this.buildPipeline(adapter, ctx, (req) => adapter.embed!(req))
    return this.executeTraced(request, ctx, chain, executor)
  }

  /** Run the chain, bracketed by trace.start / trace.end when inspecting. */
  private async executeTraced(
    request: GavioRequest,
    ctx: InterceptorContext,
    chain: InterceptorChain,
    executor: Executor,
  ): Promise<GavioResponse> {
    if (this.inspectorInstance === null) {
      return chain.execute(request, ctx, executor)
    }

    const emitter = this.inspectorInstance.beginTrace(request)
    const startedAt = performance.now()
    emitter.traceStart(request)
    try {
      const response = await chain.execute(request, ctx, executor, emitter)
      emitter.traceEndOk(response, ctx, Math.round(performance.now() - startedAt))
      return response
    } catch (error) {
      // trace.error was already emitted by the chain; close the trace and rethrow.
      emitter.traceEndError(ctx, Math.round(performance.now() - startedAt))
      throw error
    }
  }

  /**
   * Stream a completion, buffering the provider stream (F-REL-06).
   *
   * The provider stream is buffered in full so the post-interceptor pipeline
   * (guardrails, PII restore, audit) runs on the complete response before any
   * chunk reaches the caller. Pre/post interceptors run via the chain; executor
   * policies (retry, circuit breaker, cache) are not applied to the streaming
   * path.
   */
  async *stream(opts: CompleteOptions): AsyncGenerator<string> {
    const adapter = this.resolveAdapter()
    if (adapter.stream === undefined || adapter.buildStreamResponse === undefined) {
      throw new ConfigurationError(`${adapter.providerName} does not support streaming`)
    }
    const model = opts.model ?? this.modelHint ?? this.resolveModel(adapter)

    const request = new GavioRequest({
      messages: opts.messages,
      model,
      provider: coerceProvider(adapter.providerName),
      agentId: opts.agentId ?? null,
      parentTraceId: opts.parentTraceId ?? null,
      sessionId: opts.sessionId ?? null,
      options: opts.options ?? {},
      metadata: opts.metadata ?? {},
    })
    const ctx = new InterceptorContext({
      traceId: request.traceId,
      agentId: request.agentId,
      parentTraceId: request.parentTraceId,
      sessionId: request.sessionId,
      dryRun: this.dryRunMode,
    })

    const startedAt = performance.now()
    const buffer = new StreamBuffer()
    const { chain } = this.buildPipeline(adapter, ctx)
    const bufferingExecutor: Executor = async (req) => {
      for await (const chunk of adapter.stream!(req)) buffer.append(chunk)
      return adapter.buildStreamResponse!(req, buffer.text(), startedAt)
    }

    if (this.inspectorInstance === null) {
      const response = await chain.execute(request, ctx, bufferingExecutor)
      // Post-interceptors have run on the fully buffered response; emit it now.
      yield response.content
      return
    }

    // Streaming is buffered (F-REL-06), so the inspector sees the same event
    // shape as complete(): trace.start → provider.call.* → trace.end.
    const emitter = this.inspectorInstance.beginTrace(request)
    emitter.traceStart(request)
    let response: GavioResponse
    try {
      response = await chain.execute(request, ctx, bufferingExecutor, emitter)
      emitter.traceEndOk(response, ctx, Math.round(performance.now() - startedAt))
    } catch (error) {
      emitter.traceEndError(ctx, Math.round(performance.now() - startedAt))
      throw error
    }
    yield response.content
  }

  async healthCheck(): Promise<boolean> {
    return this.resolveAdapter().healthCheck()
  }

  private buildPipeline(
    adapter: ProviderAdapter,
    ctx: InterceptorContext,
    call?: Executor,
  ): { chain: InterceptorChain; executor: Executor } {
    let interceptors = [...this.interceptors]

    // Dev mode auto-wires a stdout audit sink if none was added.
    if (this.devMode && !interceptors.some(isAuditInterceptor)) {
      interceptors = [auditInterceptor(), ...interceptors]
    }

    const policies = interceptors.filter(isExecutorPolicy)
    const regular = interceptors.filter((i) => !isExecutorPolicy(i))
    const chain = new InterceptorChain(regular)

    let executor: Executor = call ?? ((request) => adapter.complete(request))
    // Wrap so the first-registered policy ends up outermost.
    for (let i = policies.length - 1; i >= 0; i--) {
      executor = this.wrapPolicy(policies[i]!, executor, ctx)
    }
    return { chain, executor }
  }

  private wrapPolicy(
    policy: ExecutorPolicy,
    inner: Executor,
    ctx: InterceptorContext,
  ): Executor {
    return async (request: GavioRequest): Promise<GavioResponse> => {
      if (ctx.dryRun && policy.dryRunSafe === false) {
        return inner(request)
      }
      return policy.around(request, ctx, inner)
    }
  }

  private resolveAdapter(): ProviderAdapter {
    if (this.adapterOverride !== undefined) return this.adapterOverride
    if (this.devMode) {
      this.adapterOverride = mockProvider({ pricing: this.pricing })
      return this.adapterOverride
    }
    if (this.providerHint === undefined) {
      throw new ConfigurationError(
        'No provider configured. Pass { provider }, { adapter }, call .withAdapter(...), ' +
          'or set { devMode: true }.',
      )
    }
    this.adapterOverride = buildAdapter(this.providerHint, this.pricing)
    return this.adapterOverride
  }

  private resolveModel(adapter: ProviderAdapter): string {
    return DEFAULT_MODELS[adapter.providerName] ?? 'mock'
  }
}
