/**
 * GavioTestKit — run interceptor chains in isolation for unit tests.
 *
 * This v0.1.0 kit drives a chain against a {@link mockProvider} and lets you
 * assert on PII detection, the redacted request, and the resulting audit record.
 */

import { InterceptorContext } from '../context.js'
import { isExecutorPolicy } from '../interceptors/base.js'
import type { Executor, ExecutorPolicy, Interceptor } from '../interceptors/base.js'
import { InterceptorChain } from '../interceptors/chain.js'
import type { AuditRecord } from '../interceptors/audit/record.js'
import { mockProvider } from '../providers/mock.js'
import type { ProviderAdapter } from '../providers/base.js'
import { GavioRequest } from '../request.js'
import type { GavioResponse } from '../response.js'
import { coerceProvider } from '../types.js'
import type { Message } from '../types.js'

/** Records the request as it reaches the provider (post-redaction). */
class CaptureInterceptor implements Interceptor {
  readonly name = '_capture'
  captured: GavioRequest | null = null

  before(request: GavioRequest): GavioRequest {
    this.captured = request
    return request
  }
}

export interface GavioTestKitOptions {
  interceptors?: Interceptor[]
  provider?: ProviderAdapter
  model?: string
}

export interface RunResult {
  response: GavioResponse
  ctx: InterceptorContext
  /** The request text as it reached the provider (post-redaction). */
  preRequestText(): string
  /** True if any (or a specific) PII entity type was detected. */
  piiDetected(entityType?: string): boolean
  /** The audit record produced for this call, if an audit interceptor ran. */
  readonly auditRecord: AuditRecord | null
}

export class GavioTestKit {
  private readonly interceptors: Interceptor[]
  private readonly provider: ProviderAdapter
  private readonly model: string

  constructor(options: GavioTestKitOptions = {}) {
    this.interceptors = [...(options.interceptors ?? [])]
    this.provider = options.provider ?? mockProvider()
    this.model = options.model ?? 'mock'
  }

  async run(input: { messages: Message[]; options?: Record<string, unknown> }): Promise<RunResult> {
    const request = new GavioRequest({
      messages: input.messages,
      model: this.model,
      provider: coerceProvider(this.provider.providerName),
      options: input.options ?? {},
    })
    const ctx = new InterceptorContext({ traceId: request.traceId })

    const capture = new CaptureInterceptor()
    const all = [...this.interceptors, capture]
    const policies = all.filter(isExecutorPolicy)
    const regular = all.filter((i) => !isExecutorPolicy(i))
    const chain = new InterceptorChain(regular)

    let executor: Executor = (req) => this.provider.complete(req)
    for (let i = policies.length - 1; i >= 0; i--) {
      executor = wrap(policies[i]!, executor, ctx)
    }

    const response = await chain.execute(request, ctx, executor)

    return {
      response,
      ctx,
      preRequestText: () => capture.captured?.promptText() ?? '',
      piiDetected: (entityType?: string): boolean => {
        if (entityType === undefined) return ctx.piiEntityTypes.length > 0
        return ctx.piiEntityTypes.includes(entityType)
      },
      get auditRecord(): AuditRecord | null {
        return response.audit
      },
    }
  }
}

function wrap(policy: ExecutorPolicy, inner: Executor, ctx: InterceptorContext): Executor {
  return (req: GavioRequest) => policy.around(req, ctx, inner)
}
