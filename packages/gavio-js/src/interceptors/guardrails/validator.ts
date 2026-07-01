/** OutputValidator interface for guardrails (F-QUA-01, F-QUA-02). */

export interface ValidationResult {
  ok: boolean
  reason?: string
}

export interface OutputValidator {
  readonly name: string
  validate(content: string): ValidationResult
}

export const passed = (): ValidationResult => ({ ok: true })
export const failed = (reason: string): ValidationResult => ({ ok: false, reason })
