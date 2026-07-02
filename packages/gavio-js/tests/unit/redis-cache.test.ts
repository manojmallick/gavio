/**
 * Tests for the Redis cache backends (F-CACHE-04).
 *
 * Skipped automatically when no Redis server is reachable — set
 * GAVIO_TEST_REDIS_URL to point at a non-default instance (default:
 * redis://localhost:6379, matching the CI service container).
 */

import { connect } from 'node:net'
import { randomUUID } from 'node:crypto'
import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import {
  hashingEmbedder,
  redisCacheBackend,
  redisVectorBackend,
  semanticCache,
} from '../../src/interceptors/cache/index.js'
import { mockProvider } from '../../src/providers/mock.js'
import type { GavioRequest } from '../../src/request.js'
import type { GavioResponse } from '../../src/response.js'

const REDIS_URL = process.env['GAVIO_TEST_REDIS_URL'] ?? 'redis://localhost:6379'

function checkRedisAvailable(url: string): Promise<boolean> {
  return new Promise((resolve) => {
    const parsed = new URL(url)
    const socket = connect({
      host: parsed.hostname || 'localhost',
      port: Number(parsed.port) || 6379,
      timeout: 500,
    })
    socket.once('connect', () => {
      socket.end()
      resolve(true)
    })
    socket.once('error', () => resolve(false))
    socket.once('timeout', () => {
      socket.destroy()
      resolve(false)
    })
  })
}

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

function ns(): string {
  return `gavio:test:${randomUUID()}`
}

const redisAvailable = await checkRedisAvailable(REDIS_URL)

describe.runIf(redisAvailable)('redisCacheBackend (F-CACHE-04)', () => {
  it('round-trips get/set/delete', async () => {
    const backend = redisCacheBackend({ url: REDIS_URL, namespace: ns() })
    await backend.set('k1', { v: 1 })
    expect(await backend.get('k1')).toEqual({ v: 1 })
    await backend.delete('k1')
    expect(await backend.get('k1')).toBeNull()
  })

  it('expires entries via TTL', async () => {
    const backend = redisCacheBackend({ url: REDIS_URL, namespace: ns() })
    await backend.set('k1', { v: 1 }, 1)
    expect(await backend.get('k1')).toEqual({ v: 1 })
    await new Promise((resolve) => setTimeout(resolve, 1300))
    expect(await backend.get('k1')).toBeNull()
  })

  it('clear only removes its own namespace', async () => {
    const backendNs = ns()
    const otherNs = ns()
    const backend = redisCacheBackend({ url: REDIS_URL, namespace: backendNs })
    const other = redisCacheBackend({ url: REDIS_URL, namespace: otherNs })
    await backend.set('a', 1)
    await backend.set('b', 2)
    await other.set('c', 3)
    await backend.clear()
    expect(await backend.get('a')).toBeNull()
    expect(await backend.get('b')).toBeNull()
    expect(await other.get('c')).toBe(3)
    await other.clear()
  })

  it('works end-to-end through semanticCache (exact hit)', async () => {
    const p = counting('cached via redis')
    const gw = new Gateway()
      .withAdapter(p.adapter)
      .use(semanticCache({ backend: redisCacheBackend({ url: REDIS_URL, namespace: ns() }) }))
    const msgs = [{ role: 'user', content: 'what is 2 + 2?' }]

    const r1 = await gw.complete({ messages: msgs })
    const r2 = await gw.complete({ messages: msgs })

    expect(p.calls()).toBe(1)
    expect(r1.cacheHit).toBe(false)
    expect(r2.cacheHit).toBe(true)
    expect(r2.cacheType).toBe('exact')
  })
})

describe.runIf(redisAvailable)('redisVectorBackend (F-CACHE-04)', () => {
  it('queries nearest by cosine similarity and clears', async () => {
    const vector = redisVectorBackend({ url: REDIS_URL, namespace: ns() })
    await vector.add([1, 0], { content: 'a' })
    await vector.add([0, 1], { content: 'b' })
    expect(await vector.query([1, 0], 0.9)).toEqual({ content: 'a' })
    expect(await vector.query([0, -1], 0.9)).toBeNull()
    await vector.clear()
    expect(await vector.query([1, 0], 0)).toBeNull()
  })

  it('works end-to-end through semanticCache (semantic hit)', async () => {
    const p = counting('semantic via redis')
    const namespace = ns()
    const gw = new Gateway().withAdapter(p.adapter).use(
      semanticCache({
        backend: redisCacheBackend({ url: REDIS_URL, namespace: `${namespace}:exact` }),
        embedder: hashingEmbedder(),
        vectorBackend: redisVectorBackend({ url: REDIS_URL, namespace: `${namespace}:vector` }),
        similarityThreshold: 0.95,
      }),
    )

    const r1 = await gw.complete({ messages: [{ role: 'user', content: 'What is 2+2?' }] })
    const r2 = await gw.complete({ messages: [{ role: 'user', content: 'what is   2 + 2 ?' }] })

    expect(p.calls()).toBe(1)
    expect(r1.cacheHit).toBe(false)
    expect(r2.cacheHit).toBe(true)
    expect(r2.cacheType).toBe('semantic')
  })
})
