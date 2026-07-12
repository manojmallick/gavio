/**
 * Gavio — the open standard AI gateway for production systems.
 *
 * Public API surface (v0.1.0):
 *
 *     import { Gateway, GavioRequest, GavioResponse, Provider } from 'gavio'
 *
 * See https://gavio.io for documentation. MIT licensed.
 */

export { VERSION } from './version.js'

export { Gateway } from './gateway.js'
export type { GatewayOptions, CompleteOptions, EmbedOptions } from './gateway.js'

export { Inspector } from './inspector/inspector.js'
export { InspectorBus } from './inspector/bus.js'
export type { InspectorConfig, InspectorMode } from './inspector/config.js'
export type { InspectorEvent } from './inspector/events.js'
export type { TraceRecord, TraceSummary } from './inspector/buffer.js'

export { GavioRequest } from './request.js'
export type { GavioRequestInit } from './request.js'
export { GavioResponse } from './response.js'
export type { GavioResponseInit } from './response.js'
export { InterceptorContext } from './context.js'
export type { InterceptorContextInit } from './context.js'

export { uuid7, newTraceId } from './ids.js'
export { PricingProvider, estimateTokens } from './pricing.js'

export {
  Provider,
  CacheType,
  PiiMode,
  Sensitivity,
  GuardrailOutcome,
  TokenUsage,
  PromptLineage,
  RagChunk,
  coerceProvider,
} from './types.js'
export type { Message, PromptLineageInit, RagChunkInit } from './types.js'

export type { Interceptor, Executor, ExecutorPolicy } from './interceptors/base.js'
export { InterceptorChain } from './interceptors/chain.js'

// errors
export {
  GavioError,
  ConfigurationError,
  ProviderError,
  ProviderUnavailableError,
  RateLimitError,
  ServerError,
  TimeoutError,
  PiiBlockedError,
  BudgetExceededError,
  GuardrailViolationError,
  ToolRuntimeError,
} from './errors.js'
