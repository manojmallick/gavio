/**
 * semanticCache (F-CACHE-01, F-CACHE-02) — two-level cache as an ExecutorPolicy.
 *
 * Exact SHA-256 cache, then optional semantic cosine cache; a hit returns the
 * cached response and skips the provider. Register outermost.
 */

import { createHash } from 'node:crypto'
import type { InterceptorContext } from '../../context.js'
import type { GavioRequest } from '../../request.js'
import { GavioResponse } from '../../response.js'
import { CacheType, TokenUsage } from '../../types.js'
import type { Executor, ExecutorPolicy } from '../base.js'
import type { CacheBackend } from './backend.js'
import { memoryCacheBackend } from './backends/memory.js'
import type { Embedder } from './embedding.js'
import { inMemoryVectorBackend, type VectorBackend } from './vector.js'

export interface SemanticCacheOptions {
  backend?: CacheBackend
  embedder?: Embedder
  vectorBackend?: VectorBackend
  exactTtlSeconds?: number
  semanticTtlSeconds?: number
  similarityThreshold?: number
}

interface CacheEntry {
  content: string
  modelVersion: string
  promptTokens: number
  completionTokens: number
}

export function semanticCache(options: SemanticCacheOptions = {}): ExecutorPolicy {
  const backend = options.backend ?? memoryCacheBackend()
  const embedder = options.embedder
  const semantic = embedder != null
  const vector = options.vectorBackend ?? (semantic ? inMemoryVectorBackend() : null)
  const exactTtl = options.exactTtlSeconds ?? 3600
  const semanticTtl = options.semanticTtlSeconds ?? 86400
  const threshold = options.similarityThreshold ?? 0.95

  function exactKey(request: GavioRequest): string {
    const opts = request.options ?? {}
    const sorted: Record<string, unknown> = {}
    for (const k of Object.keys(opts).sort()) sorted[k] = opts[k]
    const payload = JSON.stringify({
      provider: String(request.provider),
      model: request.model,
      messages: request.messages,
      options: sorted,
    })
    return 'gavio:exact:' + createHash('sha256').update(payload).digest('hex')
  }

  function hit(
    request: GavioRequest,
    ctx: InterceptorContext,
    entry: CacheEntry,
    type: CacheType,
  ): GavioResponse {
    ctx.cacheHit = true
    ctx.cacheType = type
    return new GavioResponse({
      traceId: request.traceId,
      content: entry.content,
      model: request.model,
      provider: String(request.provider),
      modelVersion: entry.modelVersion,
      usage: new TokenUsage(entry.promptTokens, entry.completionTokens),
      costUsd: 0,
      cacheHit: true,
      cacheType: type,
    })
  }

  return {
    name: 'semantic_cache',
    isExecutorPolicy: true,
    async around(
      request: GavioRequest,
      ctx: InterceptorContext,
      callNext: Executor,
    ): Promise<GavioResponse> {
      ctx.markFired('semantic_cache')

      const key = exactKey(request)
      const cached = (await backend.get(key)) as CacheEntry | null
      if (cached) return hit(request, ctx, cached, CacheType.EXACT)

      let embedding: number[] | null = null
      if (semantic && vector && embedder) {
        embedding = embedder.embed(request.promptText())
        const semHit = (await vector.query(embedding, threshold)) as CacheEntry | null
        if (semHit) return hit(request, ctx, semHit, CacheType.SEMANTIC)
      }

      const response = await callNext(request)
      const entry: CacheEntry = {
        content: response.content,
        modelVersion: response.modelVersion,
        promptTokens: response.usage.promptTokens,
        completionTokens: response.usage.completionTokens,
      }
      await backend.set(key, entry, exactTtl)
      if (embedding && vector) await vector.add(embedding, entry, semanticTtl)
      return response
    },
  }
}
