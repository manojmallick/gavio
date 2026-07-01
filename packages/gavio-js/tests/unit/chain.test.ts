import { describe, it, expect } from 'vitest'
import { InterceptorChain } from '../../src/interceptors/chain.js'
import type { Interceptor } from '../../src/interceptors/base.js'
import { InterceptorContext } from '../../src/context.js'
import { GavioRequest } from '../../src/request.js'
import { GavioResponse } from '../../src/response.js'

function makeRequest(): GavioRequest {
  return new GavioRequest({
    messages: [{ role: 'user', content: 'hi' }],
    model: 'mock',
    provider: 'mock',
  })
}

function makeResponse(req: GavioRequest): GavioResponse {
  return new GavioResponse({
    traceId: req.traceId,
    content: 'reply',
    model: req.model,
    provider: 'mock',
  })
}

describe('InterceptorChain onion ordering', () => {
  it('runs before in order and after in reverse', async () => {
    const log: string[] = []
    const mk = (name: string): Interceptor => ({
      name,
      before(req) {
        log.push(`before:${name}`)
        return req
      },
      after(res) {
        log.push(`after:${name}`)
        return res
      },
    })
    const chain = new InterceptorChain([mk('A'), mk('B'), mk('C')])
    const req = makeRequest()
    const ctx = new InterceptorContext({ traceId: req.traceId })
    await chain.execute(req, ctx, async (r) => {
      log.push('executor')
      return makeResponse(r)
    })
    expect(log).toEqual([
      'before:A',
      'before:B',
      'before:C',
      'executor',
      'after:C',
      'after:B',
      'after:A',
    ])
  })

  it('calls onError on all interceptors when the executor throws', async () => {
    const errored: string[] = []
    const mk = (name: string): Interceptor => ({
      name,
      onError() {
        errored.push(name)
      },
    })
    const chain = new InterceptorChain([mk('A'), mk('B')])
    const req = makeRequest()
    const ctx = new InterceptorContext({ traceId: req.traceId })
    await expect(
      chain.execute(req, ctx, async () => {
        throw new Error('boom')
      }),
    ).rejects.toThrow('boom')
    expect(errored).toEqual(['A', 'B'])
  })

  it('records fired interceptors on the response', async () => {
    const mk = (name: string): Interceptor => ({ name })
    const chain = new InterceptorChain([mk('A'), mk('B')])
    const req = makeRequest()
    const ctx = new InterceptorContext({ traceId: req.traceId })
    const res = await chain.execute(req, ctx, async (r) => makeResponse(r))
    expect(res.interceptorsFired).toEqual(['A', 'B'])
  })

  it('skips non-dry-run-safe interceptors in dry-run mode', async () => {
    const log: string[] = []
    const unsafe: Interceptor = {
      name: 'unsafe',
      dryRunSafe: false,
      before(req) {
        log.push('unsafe')
        return req
      },
    }
    const safe: Interceptor = {
      name: 'safe',
      before(req) {
        log.push('safe')
        return req
      },
    }
    const chain = new InterceptorChain([unsafe, safe])
    const req = makeRequest()
    const ctx = new InterceptorContext({ traceId: req.traceId, dryRun: true })
    await chain.execute(req, ctx, async (r) => makeResponse(r))
    expect(log).toEqual(['safe'])
  })
})
