import { describe, it, expect } from 'vitest'
import { GavioTestKit, mockProvider } from '../../src/testing/index.js'
import { PricingProvider, estimateTokens } from '../../src/pricing.js'
import { TokenUsage } from '../../src/types.js'
import { memoryCacheBackend } from '../../src/interceptors/cache/index.js'

describe('GavioTestKit', () => {
  it('runs against the mock provider and exposes assertions', async () => {
    const kit = new GavioTestKit({ provider: mockProvider({ response: 'pong' }) })
    const result = await kit.run({ messages: [{ role: 'user', content: 'ping' }] })
    expect(result.response.content).toBe('pong')
    expect(result.piiDetected()).toBe(false)
    expect(result.preRequestText()).toContain('ping')
  })

  it('mock provider echoes the last user message by default', async () => {
    const kit = new GavioTestKit({ provider: mockProvider() })
    const result = await kit.run({ messages: [{ role: 'user', content: 'echo me' }] })
    expect(result.response.content).toBe('[mock reply] echo me')
  })
})

describe('PricingProvider', () => {
  it('estimates cost from token usage', () => {
    const p = new PricingProvider()
    const cost = p.estimate('gpt-4o', new TokenUsage(1000, 1000))
    // 0.0025 + 0.010
    expect(cost).toBeCloseTo(0.0125, 8)
  })

  it('prefix-matches dated model ids', () => {
    const p = new PricingProvider()
    expect(p.rates('gpt-4o-2024-08-06')).toEqual([0.0025, 0.01])
  })

  it('treats unknown models as free', () => {
    const p = new PricingProvider()
    expect(p.estimate('totally-unknown-model', new TokenUsage(1000, 1000))).toBe(0)
  })
})

describe('estimateTokens', () => {
  it('uses roughly 4 chars/token', () => {
    expect(estimateTokens('')).toBe(0)
    expect(estimateTokens('abcd')).toBe(1)
    expect(estimateTokens('a'.repeat(40))).toBe(10)
  })
})

describe('memoryCacheBackend', () => {
  it('stores and retrieves values', async () => {
    const cache = memoryCacheBackend({ maxSize: 2 })
    await cache.set('a', 1)
    expect(await cache.get('a')).toBe(1)
    expect(await cache.get('missing')).toBeNull()
  })

  it('evicts least-recently-used over capacity', async () => {
    const cache = memoryCacheBackend({ maxSize: 2 })
    await cache.set('a', 1)
    await cache.set('b', 2)
    await cache.get('a') // touch a so b is LRU
    await cache.set('c', 3) // evicts b
    expect(await cache.get('b')).toBeNull()
    expect(await cache.get('a')).toBe(1)
    expect(await cache.get('c')).toBe(3)
  })
})
