/** Guardrails & output validation (F-QUA-01 schema, F-QUA-02 regex). */

export { guardrails } from './interceptor.js'
export type { GuardrailsOptions, OnFailure } from './interceptor.js'
export type { OutputValidator, ValidationResult } from './validator.js'
export { jsonSchemaValidator } from './validators/schema.js'
export { regexDenylist, regexAllowlist } from './validators/regex.js'
