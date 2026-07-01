/** VectorBackend — nearest-neighbour store for the semantic cache (F-CACHE-02). */

import { cosineSimilarity } from './embedding.js'

export interface VectorBackend {
  add(vector: number[], value: unknown, ttlSeconds?: number | null): Promise<void>
  /** Return the value of the nearest entry with similarity >= threshold. */
  query(vector: number[], threshold: number): Promise<unknown | null>
  clear(): Promise<void>
}

interface Entry {
  vector: number[]
  value: unknown
  expiresAt: number | null
}

/** Bounded, brute-force in-memory vector store (default dev backend). */
export function inMemoryVectorBackend(maxSize = 1000): VectorBackend {
  const items: Entry[] = []
  return {
    async add(vector, value, ttlSeconds): Promise<void> {
      const expiresAt = ttlSeconds ? Date.now() + ttlSeconds * 1000 : null
      items.push({ vector, value, expiresAt })
      if (items.length > maxSize) items.shift()
    },
    async query(vector, threshold): Promise<unknown | null> {
      const now = Date.now()
      let best: unknown = null
      let bestSim = threshold
      for (const item of items) {
        if (item.expiresAt !== null && now > item.expiresAt) continue
        const sim = cosineSimilarity(vector, item.vector)
        if (sim >= bestSim) {
          bestSim = sim
          best = item.value
        }
      }
      return best
    },
    async clear(): Promise<void> {
      items.length = 0
    },
  }
}
