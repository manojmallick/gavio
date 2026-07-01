/** Gateway — the entry point. Wires interceptors around a provider adapter. */

import { InterceptorContext } from './context.js'
import { ConfigurationError } from './errors.js'
import { auditInterceptor, isAuditInterceptor } from './interceptors/audit/index.js'
import { isExecutorPolicy } from './interceptors/base.js'
import type { Executor, ExecutorPolicy, Interceptor } from './interceptors/base.js'
import { InterceptorChain } from './interceptors/chain.js'
import { StreamBuffer } from './interceptors/reliability/stream-buffer.js'
import { PricingProvider } from './pricing.js'
import { buildAdapter } from './providers/index.js'
import type { ProviderAdapter } from './providers/base.js'
import { mockProvider } from './providers/mock.js'
import { GavioRequest } from './request.js'
import type { GavioResponse } from './response.js'
import { Provider, coerceProvider } from './types.js'
import type { Message } from './types.js'

export interface GatewayOptions {
  provider?: Provider | string
  model?: string
  adapter?: ProviderAdapter
  devMode?: boolean
  dryRun?: boolean
  pricing?: PricingProvider
}

export interface CompleteOptions {
  messages: Message[]
  model?: string
  agentId?: string | null
  parentTraceId?: string | null
  sessionId?: string | null
  metadata?: Record<string, unknown>
  /** Provider sampling options (temperature, maxTokens, etc.). */
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

  constructor(options: GatewayOptions = {}) {
    this.providerHint = options.provider ? coerceProvider(options.provider) : undefined
    this.modelHint = options.model
    this.adapterOverride = options.adapter
    this.devMode = options.devMode ?? false
    this.dryRunMode = options.dryRun ?? false
    this.pricing = options.pricing ?? new PricingProvider()
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
    })
    const ctx = new InterceptorContext({
      traceId: request.traceId,
      agentId: request.agentId,
      parentTraceId: request.parentTraceId,
      sessionId: request.sessionId,
      dryRun: this.dryRunMode,
    })

    const { chain, executor } = this.buildPipeline(adapter, ctx)
    return chain.execute(request, ctx, executor)
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

    const response = await chain.execute(request, ctx, bufferingExecutor)
    // Post-interceptors have run on the fully buffered response; emit it now.
    yield response.content
  }

  async healthCheck(): Promise<boolean> {
    return this.resolveAdapter().healthCheck()
  }

  private buildPipeline(
    adapter: ProviderAdapter,
    ctx: InterceptorContext,
  ): { chain: InterceptorChain; executor: Executor } {
    let interceptors = [...this.interceptors]

    // Dev mode auto-wires a stdout audit sink if none was added.
    if (this.devMode && !interceptors.some(isAuditInterceptor)) {
      interceptors = [auditInterceptor(), ...interceptors]
    }

    const policies = interceptors.filter(isExecutorPolicy)
    const regular = interceptors.filter((i) => !isExecutorPolicy(i))
    const chain = new InterceptorChain(regular)

    let executor: Executor = (request) => adapter.complete(request)
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
