/**
 * Redis cache backends (F-CACHE-04) — production-grade distributed cache.
 *
 * Zero runtime dependencies — talks RESP2 directly over `node:net` (see
 * `./resp.js`). In-memory backends remain the zero-infra default.
 */

import { randomUUID } from 'node:crypto'
import type { CacheBackend } from '../backend.js'
import { cosineSimilarity } from '../embedding.js'
import type { VectorBackend } from '../vector.js'
import { parseRedisUrl, RespClient, type RespValue } from './resp.js'

export interface RedisCacheBackendOptions {
  url?: string
  namespace?: string
}

function asStringArray(value: RespValue): string[] {
  if (!Array.isArray(value)) return []
  return value.filter((v): v is string => typeof v === 'string')
}

/** Exact-match `CacheBackend` over Redis. Keys are namespaced so `clear()` never
 * touches entries another backend/namespace wrote. */
export function redisCacheBackend(options: RedisCacheBackendOptions = {}): CacheBackend {
  const { host, port } = parseRedisUrl(options.url ?? 'redis://localhost:6379')
  const namespace = options.namespace ?? 'gavio:cache'
  const prefix = `${namespace}:`
  const indexKey = `${namespace}:index`
  const client = new RespClient(host, port)
  const namespaced = (key: string): string => prefix + key

  return {
    async get(key: string): Promise<unknown | null> {
      const raw = await client.command('GET', namespaced(key))
      if (typeof raw !== 'string') {
        await client.command('SREM', indexKey, key)
        return null
      }
      return JSON.parse(raw) as unknown
    },
    async set(key: string, value: unknown, ttlSeconds?: number | null): Promise<void> {
      const raw = JSON.stringify(value)
      if (ttlSeconds) await client.command('SET', namespaced(key), raw, 'EX', ttlSeconds)
      else await client.command('SET', namespaced(key), raw)
      await client.command('SADD', indexKey, key)
    },
    async delete(key: string): Promise<void> {
      await client.command('DEL', namespaced(key))
      await client.command('SREM', indexKey, key)
    },
    async clear(): Promise<void> {
      const keys = asStringArray(await client.command('SMEMBERS', indexKey))
      if (keys.length > 0) await client.command('DEL', ...keys.map(namespaced))
      await client.command('DEL', indexKey)
    },
  }
}

export interface RedisVectorBackendOptions {
  url?: string
  namespace?: string
}

/** Brute-force cosine-similarity `VectorBackend` over Redis — same matching
 * strategy as `inMemoryVectorBackend`, just shared across processes. */
export function redisVectorBackend(options: RedisVectorBackendOptions = {}): VectorBackend {
  const { host, port } = parseRedisUrl(options.url ?? 'redis://localhost:6379')
  const namespace = options.namespace ?? 'gavio:vector'
  const indexKey = `${namespace}:index`
  const client = new RespClient(host, port)
  const entryKey = (id: string): string => `${namespace}:${id}`

  return {
    async add(vector: number[], value: unknown, ttlSeconds?: number | null): Promise<void> {
      const id = randomUUID()
      const raw = JSON.stringify({ vector, value })
      if (ttlSeconds) await client.command('SET', entryKey(id), raw, 'EX', ttlSeconds)
      else await client.command('SET', entryKey(id), raw)
      await client.command('SADD', indexKey, id)
    },
    async query(vector: number[], threshold: number): Promise<unknown | null> {
      const ids = asStringArray(await client.command('SMEMBERS', indexKey))
      let best: unknown = null
      let bestSim = threshold
      for (const id of ids) {
        const raw = await client.command('GET', entryKey(id))
        if (typeof raw !== 'string') {
          await client.command('SREM', indexKey, id)
          continue
        }
        const entry = JSON.parse(raw) as { vector: number[]; value: unknown }
        const sim = cosineSimilarity(vector, entry.vector)
        if (sim >= bestSim) {
          bestSim = sim
          best = entry.value
        }
      }
      return best
    },
    async clear(): Promise<void> {
      const ids = asStringArray(await client.command('SMEMBERS', indexKey))
      if (ids.length > 0) await client.command('DEL', ...ids.map(entryKey))
      await client.command('DEL', indexKey)
    },
  }
}
