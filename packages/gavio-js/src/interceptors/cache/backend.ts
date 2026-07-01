/**
 * CacheBackend — the key/value contract behind the cache interceptors.
 *
 * The full SemanticCache interceptor lands in v0.2.0 (F-CACHE-01/02). v0.1.0
 * ships the backend interface and the in-memory backend so dev mode has a
 * working, dependency-free cache substrate.
 */

/** A minimal async key/value store. */
export interface CacheBackend {
  get(key: string): Promise<unknown | null>
  set(key: string, value: unknown, ttlSeconds?: number | null): Promise<void>
  delete(key: string): Promise<void>
  clear(): Promise<void>
}
