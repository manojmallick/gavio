/**
 * Embeddings for the semantic cache (F-CACHE-02).
 *
 * Zero-dependency hashed bag-of-words embedder (L2-normalised) — good enough to
 * dedup near-identical prompts. Plug in a real embedder implementing `Embedder`
 * for production semantic matching.
 */

import { createHash } from 'node:crypto'

export interface Embedder {
  embed(text: string): number[]
}

const TOKEN = /[a-z0-9]+/g

/** Deterministic hashed bag-of-words embedder. */
export function hashingEmbedder(dim = 256): Embedder {
  return {
    embed(text: string): number[] {
      const vec = new Array<number>(dim).fill(0)
      const tokens = text.toLowerCase().match(TOKEN) ?? []
      for (const token of tokens) {
        // Parity note: Python uses blake2b(digest_size=8); here we take the
        // first 8 bytes of blake2b512. Both are deterministic; the JS cache is
        // per-process so cross-language byte-parity is not required.
        const digest = createHash('blake2b512').update(token).digest()
        let n = 0n
        for (let i = 0; i < 8; i++) n = (n << 8n) | BigInt(digest[i]!)
        const bucket = Number(n % BigInt(dim))
        vec[bucket]! += 1
      }
      const norm = Math.sqrt(vec.reduce((s, x) => s + x * x, 0))
      if (norm === 0) return vec
      return vec.map((x) => x / norm)
    },
  }
}

/** Cosine similarity; safe for zero vectors. */
export function cosineSimilarity(a: number[], b: number[]): number {
  if (a.length !== b.length) throw new Error('vectors must have equal length')
  let dot = 0
  let na = 0
  let nb = 0
  for (let i = 0; i < a.length; i++) {
    dot += a[i]! * b[i]!
    na += a[i]! * a[i]!
    nb += b[i]! * b[i]!
  }
  if (na === 0 || nb === 0) return 0
  return dot / (Math.sqrt(na) * Math.sqrt(nb))
}
