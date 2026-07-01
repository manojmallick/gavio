/**
 * Gavio error hierarchy. All Gavio errors derive from {@link GavioError} so
 * callers can catch the whole family with a single check.
 */

export class GavioError extends Error {
  constructor(message?: string) {
    super(message)
    this.name = new.target.name
    Object.setPrototypeOf(this, new.target.prototype)
  }
}

/** Raised when the gateway is misconfigured (e.g. no provider set). */
export class ConfigurationError extends GavioError {}

/** Base class for provider-adapter failures. */
export class ProviderError extends GavioError {}

/** The provider could not be reached (network / health-check failure). */
export class ProviderUnavailableError extends ProviderError {}

/** The provider returned a rate-limit (HTTP 429) signal. */
export class RateLimitError extends ProviderError {}

/** The provider returned a 5xx server error. */
export class ServerError extends ProviderError {}

/** A request exceeded its configured timeout. */
export class TimeoutError extends ProviderError {}

/** PiiGuard is in BLOCK mode and detected PII in the request. */
export class PiiBlockedError extends GavioError {
  readonly entityTypes: string[]

  constructor(entityTypes: string[]) {
    const sorted = Array.from(new Set(entityTypes)).sort()
    super(`Request blocked: PII detected (${sorted.join(', ')})`)
    this.entityTypes = entityTypes
  }
}

/** A hard budget cap was exceeded. Never swallow this — surface to user. */
export class BudgetExceededError extends GavioError {}

/** The circuit breaker is open; the call was rejected without hitting the provider. */
export class CircuitOpenError extends ProviderUnavailableError {}

/** A local rate limit (requests/tokens per minute) was exceeded. */
export class RateLimitExceededError extends GavioError {}

/** The caller's role is not permitted to use the requested model (RBAC). */
export class ModelNotAllowedError extends GavioError {
  readonly role: string
  readonly model: string

  constructor(role: string, model: string) {
    super(`role ${JSON.stringify(role)} may not use model ${JSON.stringify(model)}`)
    this.role = role
    this.model = model
  }
}

/** Output failed a guardrail validator with onFailure='error'. */
export class GuardrailViolationError extends GavioError {}

/** A prompt-injection attempt was detected and the guard is in block mode. */
export class PromptInjectionError extends GavioError {
  readonly patterns: string[]

  constructor(patterns: string[]) {
    super(`prompt injection detected: ${patterns.join(', ')}`)
    this.patterns = patterns
  }
}
