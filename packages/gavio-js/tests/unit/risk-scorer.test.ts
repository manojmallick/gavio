import { describe, it, expect } from 'vitest'
import { Gateway } from '../../src/gateway.js'
import { RiskScorer, riskScorer } from '../../src/interceptors/quality/index.js'
import { auditInterceptor, type AuditSink, type AuditRecord } from '../../src/interceptors/audit/index.js'
import type { Interceptor } from '../../src/interceptors/base.js'
import type { InterceptorContext } from '../../src/context.js'
import type { GavioRequest } from '../../src/request.js'

class Collector implements AuditSink {
  records: AuditRecord[] = []
  async write(record: AuditRecord): Promise<void> {
    this.records.push(record)
  }
}

/** Seeds the context with the raw signals RiskScorer reads. */
function seeder(opts: {
  pii?: string[]
  guardrail?: string | null
  injection?: number | null
}): Interceptor {
  return {
    name: 'seeder',
    async before(request: GavioRequest, ctx: InterceptorContext): Promise<GavioRequest> {
      if (opts.pii) ctx.recordPii(opts.pii)
      ctx.guardrailOutcome = opts.guardrail ?? null
      ctx.riskScore = opts.injection ?? null
      return request
    },
  }
}

describe('risk scoring (F-QUA-06)', () => {
  it('scores zero with no signals', () => {
    expect(new RiskScorer().score(0, null, null)).toBe(0)
  })

  it('weights each signal', () => {
    const s = new RiskScorer() // 0.3 / 0.4 / 0.3, saturation 4
    expect(s.score(2, null, null)).toBeCloseTo(0.15, 9) // 0.3 * 0.5
    expect(s.score(0, 'FAIL', null)).toBeCloseTo(0.4, 9)
    expect(s.score(0, 'HITL', null)).toBeCloseTo(0.24, 9) // 0.4 * 0.6
    expect(s.score(0, null, 0.5)).toBeCloseTo(0.15, 9) // 0.3 * 0.5
  })

  it('saturates and clamps to [0,1]', () => {
    const s = new RiskScorer()
    expect(s.score(10, 'FAIL', 1)).toBe(1)
    expect(s.score(10, 'FAIL', 5)).toBe(1)
  })

  it('honours custom weights', () => {
    const s = new RiskScorer({ pii: 1, guardrail: 0, injection: 0, piiSaturation: 2 })
    expect(s.score(1, 'FAIL', 1)).toBe(0.5)
  })

  it('writes the composite to the audit record', async () => {
    const sink = new Collector()
    const gw = new Gateway({ devMode: true })
      .use(auditInterceptor({ sink })) // outermost → after runs last
      .use(seeder({ pii: ['EMAIL', 'IBAN'], guardrail: 'FAIL', injection: 1 }))
      .use(riskScorer())
    await gw.complete({ messages: [{ role: 'user', content: 'hi' }] })

    // pii 0.5 (2/4) → 0.15; guardrail 0.4; injection 0.3 → 0.85
    expect(sink.records[0]!.riskScore).toBeCloseTo(0.85, 9)
  })
})
