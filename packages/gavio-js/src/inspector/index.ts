/** Gavio Inspector (F-DX-09/F-DX-10) — local trace inspection for the gateway. */

export { Inspector } from './inspector.js'
export type { InspectorOptions } from './inspector.js'
export { buildDag, buildSessions, buildStats } from './analytics.js'
export type {
  Dag,
  DagNode,
  SessionAggregate,
  Stats,
  StatsAggregate,
  SubtreeRollup,
  SummaryLike,
  UsageJson,
} from './analytics.js'
export {
  EXPORT_FORMATS,
  SYNTHETIC_FIXTURES,
  exportTrace,
  sanitizeMessages,
  sanitizeText,
} from './export.js'
export type { ExportFormat, ExportMessage, ExportableTrace } from './export.js'
export { InspectorBus } from './bus.js'
export type { InspectorSubscriber } from './bus.js'
export { TraceBuffer, DEFAULT_MAX_EVENTS_PER_TRACE } from './buffer.js'
export type { TraceBufferOptions, TraceRecord, TraceSummary } from './buffer.js'
export {
  DEFAULT_INSPECTOR_PORT,
  DEFAULT_MAX_TRACES,
  envInspectEnabled,
  resolveInspectorConfig,
} from './config.js'
export type { InspectorConfig, InspectorMode, ResolvedInspectorConfig } from './config.js'
export { TraceEmitter } from './emitter.js'
export type { ErrorOrigin } from './emitter.js'
export {
  interceptorEndData,
  interceptorEndDataWithDiff,
  interceptorStartData,
  makeEvent,
  maskSecrets,
  providerCallEndData,
  providerCallStartData,
  traceEndData,
  traceEndDataWithContent,
  traceErrorData,
  traceStartData,
  traceStartDataWithMessages,
} from './events.js'
export type {
  InspectorEvent,
  InspectorEventType,
  InterceptorEndMeta,
  MutationDiff,
  ProviderCallEndMeta,
  TraceEndMeta,
  TraceErrorMeta,
  TraceStartMeta,
} from './events.js'
export { InspectorServer, pipelineLints } from './server.js'
export type { InspectorServerOptions, PipelineInfo, ReplayHandler } from './server.js'
export { INSPECTOR_UI_HTML } from './ui.js'
