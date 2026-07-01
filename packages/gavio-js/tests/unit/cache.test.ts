import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import {
  semanticCache,
  hashingEmbedder,
  cosineSimilarity,
  memoryCacheBackend,
} from '../../src/interceptors/cache/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import type { GavioRequest } from '../../src/request.js'
import type { GavioResponse } from '../../src/response.js'

function counting(response: string) {
  const base = mockProvider({ response })
  let calls = 0
  return {
    adapter: {
      providerName: base.providerName,
      async complete(req: GavioRequest): Promise<GavioResponse> {
        calls += 1
        return base.complete(req)
      },
      async healthCheck() {
        return true
      },
    },
    calls: () => calls,
  }
}

describe('semanticCache — exact (F-CACHE-01)', () => {
  it('hits the cache and skips the provider', async () => {
    const p = counting('cached answer')
    const gw = new Gateway().withAdapter(p.adapter).use(semanticCache())
    const msgs = [{ role: 'user', content: 'what is 2 + 2?' }]

    const r1 = await gw.complete({ messages: msgs })
    const r2 = await gw.complete({ messages: msgs })

    expect(p.calls()).toBe(1)
    expect(r1.cacheHit).toBe(false)
    expect(r2.cacheHit).toBe(true)
    expect(r2.cacheType).toBe('exact')
    expect(r2.costUsd).toBe(0)
    expect(r2.content).toBe(r1.content)
  })

  it('misses on a different prompt', async () => {
    const p = counting('x')
    const gw = new Gateway().withAdapter(p.adapter).use(semanticCache())
    await gw.complete({ messages: [{ role: 'user', content: 'alpha' }] })
    await gw.complete({ messages: [{ role: 'user', content: 'beta' }] })
    expect(p.calls()).toBe(2)
  })
})

describe('semanticCache — semantic (F-CACHE-02)', () => {
  it('hits on a whitespace/case variant', async () => {
    const p = counting('semantic answer')
    const gw = new Gateway()
      .withAdapter(p.adapter)
      .use(semanticCache({ embedder: hashingEmbedder(), similarityThreshold: 0.95 }))

    const r1 = await gw.complete({ messages: [{ role: 'user', content: 'What is 2+2?' }] })
    const r2 = await gw.complete({ messages: [{ role: 'user', content: 'what is   2 + 2 ?' }] })

    expect(p.calls()).toBe(1)
    expect(r1.cacheHit).toBe(false)
    expect(r2.cacheHit).toBe(true)
    expect(r2.cacheType).toBe('semantic')
  })

  it('is disabled without an embedder', async () => {
    const p = counting('x')
    const gw = new Gateway().withAdapter(p.adapter).use(semanticCache())
    await gw.complete({ messages: [{ role: 'user', content: 'What is 2+2?' }] })
    await gw.complete({ messages: [{ role: 'user', content: 'what is 2 + 2 ?' }] })
    expect(p.calls()).toBe(2)
  })
})

describe('hashingEmbedder + cosine', () => {
  it('scores variants high and different texts low', () => {
    const e = hashingEmbedder()
    const a = e.embed('What is 2+2?')
    const b = e.embed('what is   2 + 2 ?')
    const c = e.embed('completely different sentence about cats')
    expect(cosineSimilarity(a, b)).toBeGreaterThan(0.99)
    expect(cosineSimilarity(a, c)).toBeLessThan(0.5)
  })
})

describe('memoryCacheBackend', () => {
  it('stores and evicts (LRU)', async () => {
    const b = memoryCacheBackend({ maxSize: 2 })
    await b.set('k1', { v: 1 })
    expect(await b.get('k1')).toEqual({ v: 1 })
    await b.set('k2', { v: 2 })
    await b.set('k3', { v: 3 })
    expect(await b.get('k1')).toBeNull()
    expect(await b.get('k3')).toEqual({ v: 3 })
  })
})
