/** AuditRecord — the immutable, per-request audit entry. */

import { createHash } from 'node:crypto'
import { PromptLineage, TokenUsage } from '../../types.js'

export const SCHEMA_VERSION = '1.0'

function sha256(text: string): string {
  return createHash('sha256').update(text, 'utf-8').digest('hex')
}

/** Deterministic JSON with keys sorted at every nesting level. */
function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null'
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`
  const obj = value as Record<string, unknown>
  const parts = Object.keys(obj)
    .sort()
    .map((k) => `${JSON.stringify(k)}:${stableStringify(obj[k])}`)
  return `{${parts.join(',')}}`
}

export interface AuditRecordInit {
  traceId: string
  provider: string
  model: string
  timestampUtc: string
  parentTraceId?: string | null
  agentId?: string | null
  sessionId?: string | null
  subjectId?: string | null
  modelVersion?: string
  promptHash?: string
  responseHash?: string
  tokenUsage?: TokenUsage
  costUsd?: number
  latencyMs?: number
  piiEntityTypes?: string[]
  piiEntityCounts?: Record<string, number>
  interceptorsFired?: string[]
  cacheHit?: boolean
  cacheType?: string | null
  guardrailOutcome?: string | null
  riskScore?: number | null
  lineage?: PromptLineage | null
  previousHash?: string
  schemaVersion?: string
}

/**
 * One append-only audit entry. Carries metadata only — never raw content.
 *
 * `promptHash` / `responseHash` are SHA-256 digests so the entry is verifiable
 * without storing sensitive text. `previousHash` is reserved for the v0.2.0
 * hash-chain (F-OBS-02); empty in v0.1.0.
 */
export class AuditRecord {
  traceId: string
  provider: string
  model: string
  timestampUtc: string
  parentTraceId: string | null
  agentId: string | null
  sessionId: string | null
  subjectId: string | null
  modelVersion: string
  promptHash: string
  responseHash: string
  tokenUsage: TokenUsage
  costUsd: number
  latencyMs: number
  piiEntityTypes: string[]
  piiEntityCounts: Record<string, number>
  interceptorsFired: string[]
  cacheHit: boolean
  cacheType: string | null
  guardrailOutcome: string | null
  riskScore: number | null
  lineage: PromptLineage | null
  previousHash: string
  schemaVersion: string

  constructor(init: AuditRecordInit) {
    this.traceId = init.traceId
    this.provider = init.provider
    this.model = init.model
    this.timestampUtc = init.timestampUtc
    this.parentTraceId = init.parentTraceId ?? null
    this.agentId = init.agentId ?? null
    this.sessionId = init.sessionId ?? null
    this.subjectId = init.subjectId ?? null
    this.modelVersion = init.modelVersion ?? ''
    this.promptHash = init.promptHash ?? ''
    this.responseHash = init.responseHash ?? ''
    this.tokenUsage = init.tokenUsage ?? new TokenUsage()
    this.costUsd = init.costUsd ?? 0.0
    this.latencyMs = init.latencyMs ?? 0
    this.piiEntityTypes = init.piiEntityTypes ?? []
    this.piiEntityCounts = init.piiEntityCounts ?? {}
    this.interceptorsFired = init.interceptorsFired ?? []
    this.cacheHit = init.cacheHit ?? false
    this.cacheType = init.cacheType ?? null
    this.guardrailOutcome = init.guardrailOutcome ?? null
    this.riskScore = init.riskScore ?? null
    this.lineage = init.lineage ?? null
    this.previousHash = init.previousHash ?? ''
    this.schemaVersion = init.schemaVersion ?? SCHEMA_VERSION
  }

  static nowUtc(): string {
    return new Date().toISOString()
  }

  static hashText(text: string): string {
    return sha256(text)
  }

  toJSON(): Record<string, unknown> {
    return {
      traceId: this.traceId,
      parentTraceId: this.parentTraceId,
      agentId: this.agentId,
      sessionId: this.sessionId,
      subjectId: this.subjectId,
      provider: this.provider,
      model: this.model,
      modelVersion: this.modelVersion,
      timestampUtc: this.timestampUtc,
      promptHash: this.promptHash,
      responseHash: this.responseHash,
      tokenUsage: this.tokenUsage.toJSON(),
      costUsd: this.costUsd,
      latencyMs: this.latencyMs,
      piiEntityTypes: this.piiEntityTypes,
      piiEntityCounts: this.piiEntityCounts,
      interceptorsFired: this.interceptorsFired,
      cacheHit: this.cacheHit,
      cacheType: this.cacheType,
      guardrailOutcome: this.guardrailOutcome,
      riskScore: this.riskScore,
      lineage: this.lineage ? this.lineage.toJSON() : null,
      previousHash: this.previousHash,
      schemaVersion: this.schemaVersion,
    }
  }

  /** Stable JSON with recursively sorted keys — used for the v0.2.0 hash chain. */
  toCanonicalJson(): string {
    return stableStringify(this.toJSON())
  }

  /** Hash of this record's content — used to build the v0.2.0 chain. */
  contentHash(): string {
    return sha256(this.toCanonicalJson())
  }
}
