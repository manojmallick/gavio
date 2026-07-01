/** In-memory cache backend (F-CACHE-03) — default zero-dependency dev backend. */

import type { CacheBackend } from '../backend.js'

interface Entry {
  value: unknown
  expiresAt: number | null
}

export interface MemoryCacheBackendOptions {
  maxSize?: number
}

/** LRU-bounded, optionally TTL'd in-process cache. Not shared across processes. */
class MemoryBackend implements CacheBackend {
  readonly maxSize: number
  // Map preserves insertion order, which we use for LRU eviction.
  private store = new Map<string, Entry>()

  constructor(maxSize = 1000) {
    this.maxSize = maxSize
  }

  async get(key: string): Promise<unknown | null> {
    const entry = this.store.get(key)
    if (entry === undefined) return null
    if (entry.expiresAt !== null && now() > entry.expiresAt) {
      this.store.delete(key)
      return null
    }
    // Move to end (most-recently-used).
    this.store.delete(key)
    this.store.set(key, entry)
    return entry.value
  }

  async set(key: string, value: unknown, ttlSeconds?: number | null): Promise<void> {
    const expiresAt = ttlSeconds ? now() + ttlSeconds * 1000 : null
    this.store.delete(key)
    this.store.set(key, { value, expiresAt })
    while (this.store.size > this.maxSize) {
      const oldest = this.store.keys().next().value
      if (oldest === undefined) break
      this.store.delete(oldest)
    }
  }

  async delete(key: string): Promise<void> {
    this.store.delete(key)
  }

  async clear(): Promise<void> {
    this.store.clear()
  }

  get size(): number {
    return this.store.size
  }
}

function now(): number {
  return Date.now()
}

/** Factory: build an in-memory cache backend. */
export function memoryCacheBackend(options: MemoryCacheBackendOptions = {}): CacheBackend {
  return new MemoryBackend(options.maxSize ?? 1000)
}
