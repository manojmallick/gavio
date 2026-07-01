/**
 * Caching substrate. The SemanticCache interceptor ships in v0.2.0; v0.1.0
 * exposes the CacheBackend interface and the in-memory backend only.
 */

export type { CacheBackend } from './backend.js'
export { memoryCacheBackend } from './backends/memory.js'
export type { MemoryCacheBackendOptions } from './backends/memory.js'
