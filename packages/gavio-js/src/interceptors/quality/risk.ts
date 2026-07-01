/**
 * RiskScorer (F-QUA-06) — a composite risk score from per-request signals.
 *
 * Folds the signals other interceptors leave on the {@link InterceptorContext}
 * — PII entities found, guardrail outcome, and the prompt-injection risk — into
 * a single score in `[0, 1]` written to `ctx.riskScore` (and thus the audit
 * record). Register it *inside* the audit interceptor so audit sees the composite.
 */

import type { InterceptorContext } from '../../context.js'
import type { GavioResponse } from '../../response.js'
import type { Interceptor } from '../base.js'

export interface RiskWeights {
  pii?: number
  guardrail?: number
  injection?: number
  /** PII entity count at which the PII signal saturates to 1.0 (<= 0 → any PII = 1.0). */
  piiSaturation?: number
}

// Guardrail outcome → its contribution before weighting.
const GUARDRAIL_SIGNAL: Record<string, number> = { FAIL: 1.0, HITL: 0.6 }

export class RiskScorer implements Interceptor {
  readonly name = 'risk_scorer'
  readonly dryRunSafe = true

  private readonly pii: number
  private readonly guardrail: number
  private readonly injection: number
  private readonly piiSaturation: number

  constructor(weights: RiskWeights = {}) {
    this.pii = weights.pii ?? 0.3
    this.guardrail = weights.guardrail ?? 0.4
    this.injection = weights.injection ?? 0.3
    this.piiSaturation = weights.piiSaturation ?? 4
  }

  /** Compute the composite risk score from the three raw signals. */
  score(piiCount: number, guardrailOutcome: string | null, injectionScore: number | null): number {
    let piiSignal = 0
    if (piiCount > 0) {
      piiSignal = this.piiSaturation <= 0 ? 1 : Math.min(1, piiCount / this.piiSaturation)
    }
    const guardrailSignal = GUARDRAIL_SIGNAL[guardrailOutcome ?? ''] ?? 0
    const injectionSignal = injectionScore ?? 0
    const composite =
      this.pii * piiSignal + this.guardrail * guardrailSignal + this.injection * injectionSignal
    return Math.max(0, Math.min(1, composite))
  }

  async after(response: GavioResponse, ctx: InterceptorContext): Promise<GavioResponse> {
    const piiCount = Object.values(ctx.piiEntityCounts).reduce((a, b) => a + b, 0)
    ctx.riskScore = this.score(piiCount, ctx.guardrailOutcome, ctx.riskScore)
    return response
  }
}

/** Build a risk scorer. */
export function riskScorer(weights: RiskWeights = {}): RiskScorer {
  return new RiskScorer(weights)
}
