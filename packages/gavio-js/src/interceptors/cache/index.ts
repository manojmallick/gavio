/** Caching (F-CACHE-01 exact, F-CACHE-02 semantic, F-CACHE-03 in-memory). */

export type { CacheBackend } from './backend.js'
export { memoryCacheBackend } from './backends/memory.js'
export type { MemoryCacheBackendOptions } from './backends/memory.js'
export { semanticCache } from './interceptor.js'
export type { SemanticCacheOptions } from './interceptor.js'
export { hashingEmbedder, cosineSimilarity } from './embedding.js'
export type { Embedder } from './embedding.js'
export { inMemoryVectorBackend } from './vector.js'
export type { VectorBackend } from './vector.js'
