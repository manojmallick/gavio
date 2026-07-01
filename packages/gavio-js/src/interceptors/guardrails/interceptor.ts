/**
 * guardrails (F-QUA-01, F-QUA-02) — validate responses, act on failure.
 *
 * An ExecutorPolicy so it can re-run the provider on failure. Records the
 * outcome in ctx.guardrailOutcome for the audit trail.
 */

import type { InterceptorContext } from '../../context.js'
import { GuardrailViolationError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { GavioResponse } from '../../response.js'
import type { Executor, ExecutorPolicy } from '../base.js'
import type { OutputValidator } from './validator.js'

export type OnFailure = 'error' | 'retry' | 'warn'

export interface GuardrailsOptions {
  validators: OutputValidator[]
  onFailure?: OnFailure
  maxRetries?: number
}

export function guardrails(options: GuardrailsOptions): ExecutorPolicy {
  const { validators, onFailure = 'error', maxRetries = 2 } = options
  return {
    name: 'guardrails',
    isExecutorPolicy: true,
    async around(
      request: GavioRequest,
      ctx: InterceptorContext,
      callNext: Executor,
    ): Promise<GavioResponse> {
      ctx.markFired('guardrails')
      const attempts = onFailure === 'retry' ? maxRetries + 1 : 1
      let response: GavioResponse | undefined
      let failures: string[] = []

      for (let attempt = 0; attempt < attempts; attempt++) {
        response = await callNext(request)
        failures = []
        for (const v of validators) {
          const result = v.validate(response.content)
          if (!result.ok) failures.push(`${v.name}: ${result.reason ?? ''}`)
        }
        if (failures.length === 0) {
          ctx.guardrailOutcome = 'PASS'
          return response
        }
      }

      ctx.guardrailOutcome = 'FAIL'
      if (onFailure === 'warn') return response as GavioResponse
      throw new GuardrailViolationError(failures.join('; '))
    },
  }
}
