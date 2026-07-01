/** rateLimiter (F-GOV-03) — fixed-window requests/tokens per minute per scope. */

import type { InterceptorContext } from '../../context.js'
import { RateLimitExceededError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Interceptor } from '../base.js'
import type { Scope } from './budget.js'

export interface RateLimiterOptions {
  maxRequestsPerMinute?: number
  maxTokensPerMinute?: number
  scope?: Scope
}

function scopeKey(scope: Scope, ctx: InterceptorContext): string {
  if (scope === 'agent') return `agent:${ctx.agentId ?? 'unknown'}`
  if (scope === 'session') return `session:${ctx.sessionId ?? 'unknown'}`
  return 'global'
}

interface WindowState {
  minute: number
  requests: number
  tokens: number
}

export function rateLimiter(options: RateLimiterOptions = {}): Interceptor {
  const { maxRequestsPerMinute, maxTokensPerMinute, scope = 'global' } = options
  const windows = new Map<string, WindowState>()

  function windowFor(ctx: InterceptorContext): WindowState {
    const minute = Math.floor(Date.now() / 60000)
    const key = scopeKey(scope, ctx)
    let w = windows.get(key)
    if (!w || w.minute !== minute) {
      w = { minute, requests: 0, tokens: 0 }
      windows.set(key, w)
    }
    return w
  }

  return {
    name: 'rate_limiter',
    before(request: GavioRequest, ctx: InterceptorContext): GavioRequest {
      const w = windowFor(ctx)
      if (maxRequestsPerMinute !== undefined && w.requests >= maxRequestsPerMinute) {
        throw new RateLimitExceededError(`rate limit: ${maxRequestsPerMinute} requests/min exceeded`)
      }
      if (maxTokensPerMinute !== undefined && w.tokens >= maxTokensPerMinute) {
        throw new RateLimitExceededError(`rate limit: ${maxTokensPerMinute} tokens/min exceeded`)
      }
      w.requests += 1
      return request
    },
    after(response: GavioResponse, ctx: InterceptorContext): GavioResponse {
      if (maxTokensPerMinute !== undefined) {
        windowFor(ctx).tokens += response.usage.totalTokens
      }
      return response
    },
  }
}
