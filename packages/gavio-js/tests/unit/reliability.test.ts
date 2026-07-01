import { describe, it, expect } from 'vitest'
import {
  retryInterceptor,
  timeoutPolicy,
  fallbackChain,
} from '../../src/interceptors/reliability/index.js'
import { InterceptorContext } from '../../src/context.js'
import { GavioRequest } from '../../src/request.js'
import { GavioResponse } from '../../src/response.js'
import {
  ServerError,
  RateLimitError,
  TimeoutError,
  ProviderError,
} from '../../src/errors.js'
import { mockProvider } from '../../src/providers/mock.js'

function req(): GavioRequest {
  return new GavioRequest({
    messages: [{ role: 'user', content: 'hi' }],
    model: 'mock',
    provider: 'mock',
  })
}
const ctx = (r: GavioRequest) => new InterceptorContext({ traceId: r.traceId })
const ok = (r: GavioRequest) =>
  new GavioResponse({ traceId: r.traceId, content: 'ok', model: r.model, provider: 'mock' })

describe('retryInterceptor', () => {
  it('succeeds after transient failures', async () => {
    let calls = 0
    const policy = retryInterceptor({ maxAttempts: 3, jitter: false, sleep: async () => {} })
    const r = req()
    const res = await policy.around(r, ctx(r), async (request) => {
      calls += 1
      if (calls < 3) throw new ServerError('5xx')
      return ok(request)
    })
    expect(calls).toBe(3)
    expect(res.content).toBe('ok')
  })

  it('exhausts attempts and rethrows the last error', async () => {
    let calls = 0
    const policy = retryInterceptor({ maxAttempts: 2, jitter: false, sleep: async () => {} })
    const r = req()
    await expect(
      policy.around(r, ctx(r), async () => {
        calls += 1
        throw new RateLimitError('429')
      }),
    ).rejects.toBeInstanceOf(RateLimitError)
    expect(calls).toBe(2)
  })

  it('does not retry a non-transient error', async () => {
    let calls = 0
    const policy = retryInterceptor({ maxAttempts: 3, jitter: false, sleep: async () => {} })
    const r = req()
    await expect(
      policy.around(r, ctx(r), async () => {
        calls += 1
        throw new Error('non-transient')
      }),
    ).rejects.toThrow('non-transient')
    expect(calls).toBe(1)
  })
})

describe('timeoutPolicy', () => {
  it('passes through a fast call', async () => {
    const policy = timeoutPolicy({ timeoutSeconds: 1 })
    const r = req()
    const res = await policy.around(r, ctx(r), async (request) => ok(request))
    expect(res.content).toBe('ok')
  })

  it('throws TimeoutError when the call is too slow', async () => {
    const policy = timeoutPolicy({ timeoutSeconds: 0.05 })
    const r = req()
    await expect(
      policy.around(r, ctx(r), async (request) => {
        await new Promise((res) => setTimeout(res, 200))
        return ok(request)
      }),
    ).rejects.toBeInstanceOf(TimeoutError)
  })
})

describe('fallbackChain', () => {
  it('falls back to a secondary adapter on provider error', async () => {
    const fallback = mockProvider({ response: 'from fallback' })
    const policy = fallbackChain({ fallbacks: [fallback] })
    const r = req()
    const res = await policy.around(r, ctx(r), async () => {
      throw new ProviderError('primary down')
    })
    expect(res.content).toBe('from fallback')
    expect(res.provider).toBe('mock')
  })

  it('returns the primary result when it succeeds', async () => {
    const policy = fallbackChain({ fallbacks: [mockProvider({ response: 'fb' })] })
    const r = req()
    const res = await policy.around(r, ctx(r), async (request) => ok(request))
    expect(res.content).toBe('ok')
  })

  it('rethrows non-provider errors without falling back', async () => {
    const policy = fallbackChain({ fallbacks: [mockProvider()] })
    const r = req()
    await expect(
      policy.around(r, ctx(r), async () => {
        throw new Error('logic bug')
      }),
    ).rejects.toThrow('logic bug')
  })
})
