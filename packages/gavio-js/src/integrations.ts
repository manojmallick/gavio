/** Dependency-light integration catalog helpers. */

import { createHash } from 'node:crypto'

export const ADAPTER_SCHEMA_VERSION = 'gavio.integration-adapter.v1'
const CONTENT_KEYS = new Set([
  'messages',
  'content',
  'diff',
  'prompt',
  'response',
  'output',
  'renderedPrompt',
  'rendered_prompt',
])

export interface IntegrationRecipe {
  id: string
  name: string
  category:
    | 'gateway'
    | 'gateway_observability'
    | 'observability'
    | 'eval'
    | 'framework'
    | 'provider_sdk'
  externalOwns: string[]
  gavioOwns: string[]
  gavioSurfaces: string[]
  recommendedExporters: Array<'jsonl' | 'otel'>
  metadata: Record<string, string>
  docsPath: string
  examplePath: string
}

export interface IntegrationAdapterPayload {
  schemaVersion: typeof ADAPTER_SCHEMA_VERSION
  adapter: string
  target: string
  kind: IntegrationRecipe['category']
  payload: Record<string, unknown>
}

export function listIntegrations(options: { category?: string } = {}): IntegrationRecipe[] {
  const recipes =
    options.category === undefined
      ? INTEGRATIONS
      : INTEGRATIONS.filter((recipe) => recipe.category === options.category)
  return recipes.map(cloneRecipe)
}

export function getIntegration(id: string): IntegrationRecipe {
  const recipe = INTEGRATIONS.find((item) => item.id === id)
  if (recipe === undefined) {
    const known = INTEGRATIONS.map((item) => item.id).sort().join(', ')
    throw new Error(`unknown Gavio integration "${id}"; known: ${known}`)
  }
  return cloneRecipe(recipe)
}

export function integrationMetadata(
  id: string,
  overrides: Record<string, string | number | boolean | null | undefined> = {},
): Record<string, string> {
  const base = getIntegration(id).metadata
  const out: Record<string, string> = { ...base }
  for (const [key, value] of Object.entries(overrides)) {
    if (value !== null && value !== undefined) out[key] = String(value)
  }
  return out
}

export function compatibilityMatrix(): Array<Omit<IntegrationRecipe, 'metadata'>> {
  return INTEGRATIONS.map((recipe) => {
    const { metadata: _metadata, ...row } = cloneRecipe(recipe)
    return row
  })
}

export function integrationAdapterPayload(
  id: string,
  source: Record<string, unknown> | undefined = undefined,
  options: {
    metadata?: Record<string, unknown>
    operation?: string
  } = {},
): IntegrationAdapterPayload {
  const recipe = getIntegration(id)
  const sourceRecord = sourceToRecord(source)
  const labels = adapterMetadata(recipe.id, sourceRecord, options.metadata ?? {})
  const summary = adapterSummary(sourceRecord)
  const operation = options.operation ?? defaultOperation(recipe.id)
  return {
    schemaVersion: ADAPTER_SCHEMA_VERSION,
    adapter: recipe.id,
    target: recipe.id,
    kind: recipe.category,
    payload: payloadFor(recipe.id, labels, summary, operation),
  }
}

export function litellmAdapterPayload(
  source?: Record<string, unknown>,
  options?: { metadata?: Record<string, unknown>; operation?: string },
): IntegrationAdapterPayload {
  return integrationAdapterPayload('litellm', source, options)
}

export function promptfooAdapterPayload(
  source?: Record<string, unknown>,
  options?: { metadata?: Record<string, unknown>; operation?: string },
): IntegrationAdapterPayload {
  return integrationAdapterPayload('promptfoo', source, options)
}

export function langfuseAdapterPayload(
  source?: Record<string, unknown>,
  options?: { metadata?: Record<string, unknown>; operation?: string },
): IntegrationAdapterPayload {
  return integrationAdapterPayload('langfuse', source, options)
}

export function openlitAdapterPayload(
  source?: Record<string, unknown>,
  options?: { metadata?: Record<string, unknown>; operation?: string },
): IntegrationAdapterPayload {
  return integrationAdapterPayload('openlit', source, options)
}

export function langchainAdapterPayload(
  source?: Record<string, unknown>,
  options?: { metadata?: Record<string, unknown>; operation?: string },
): IntegrationAdapterPayload {
  return integrationAdapterPayload('langchain', source, options)
}

export function langgraphAdapterPayload(
  source?: Record<string, unknown>,
  options?: { metadata?: Record<string, unknown>; operation?: string },
): IntegrationAdapterPayload {
  return integrationAdapterPayload('langgraph', source, options)
}

export function vercelAiSdkAdapterPayload(
  source?: Record<string, unknown>,
  options?: { metadata?: Record<string, unknown>; operation?: string },
): IntegrationAdapterPayload {
  return integrationAdapterPayload('vercel-ai-sdk', source, options)
}

function cloneRecipe(recipe: IntegrationRecipe): IntegrationRecipe {
  return {
    ...recipe,
    externalOwns: [...recipe.externalOwns],
    gavioOwns: [...recipe.gavioOwns],
    gavioSurfaces: [...recipe.gavioSurfaces],
    recommendedExporters: [...recipe.recommendedExporters],
    metadata: { ...recipe.metadata },
  }
}

function payloadFor(
  id: string,
  labels: Record<string, unknown>,
  summary: Record<string, unknown>,
  operation: string,
): Record<string, unknown> {
  const tags = adapterTags(labels, id)
  const traceId = asString(summary['traceId']) ?? asString(labels['trace_id']) ?? ''
  const merged = cleanRecord({ ...labels, ...prefixSummary(summary) })
  if (id === 'litellm') {
    return {
      completionKwargs: {
        metadata: merged,
        extraHeaders: traceHeaders(traceId, id),
      },
    }
  }
  if (id === 'promptfoo') {
    return {
      defaultTest: {
        metadata: labels,
        assert: [
          {
            type: 'javascript',
            value: "context.vars.gavio.status !== 'error'",
            metric: 'gavio_status',
          },
          {
            type: 'javascript',
            value: '(context.vars.gavio.failedCases ?? 0) === 0',
            metric: 'gavio_eval_failures',
          },
        ],
      },
      vars: { gavio: summary },
    }
  }
  if (id === 'langfuse') {
    return {
      trace: {
        id: traceId,
        name: operation,
        metadata: merged,
        tags,
      },
      generation: {
        id: traceId === '' ? 'gavio:generation' : `${traceId}:generation`,
        traceId,
        name: 'gavio.request',
        model: summary['model'],
        metadata: summary,
      },
    }
  }
  if (id === 'openlit') {
    return {
      span: {
        name: operation,
        attributes: {
          ...cleanRecord({
            'gavio.integration': id,
            'gavio.trace_id': traceId,
            'gavio.event_type': summary['eventType'],
            'gavio.status': summary['status'],
            'gavio.latency_ms': summary['latencyMs'],
            'gen_ai.system': summary['provider'],
            'gen_ai.request.model': summary['model'],
            'gen_ai.usage.cost': summary['costUsd'],
          }),
          ...prefixLabels(labels),
        },
      },
    }
  }
  if (id === 'langchain') {
    return {
      runnableConfig: {
        run_name: operation,
        metadata: merged,
        tags,
      },
    }
  }
  if (id === 'langgraph') {
    const workflow = asString(labels['workflow']) ?? (traceId === '' ? 'gavio' : traceId)
    return {
      runnableConfig: {
        run_name: operation,
        metadata: merged,
        tags,
        configurable: {
          thread_id: workflow,
          gavio_trace_id: traceId,
        },
      },
    }
  }
  if (id === 'vercel-ai-sdk') {
    return {
      request: {
        headers: traceHeaders(traceId, id),
        experimental_telemetry: {
          isEnabled: true,
          functionId: operation,
          metadata: merged,
        },
      },
    }
  }
  return { metadata: merged, summary }
}

function sourceToRecord(source: Record<string, unknown> | undefined): Record<string, unknown> {
  return source === undefined ? {} : structuredClone(source)
}

function adapterMetadata(
  id: string,
  source: Record<string, unknown>,
  metadata: Record<string, unknown>,
): Record<string, unknown> {
  const sanitized = sanitizeMetadata(metadata)
  const labels: Record<string, unknown> = {
    ...integrationMetadata(id),
    ...(recordValue(sanitized) ?? {}),
  }
  const traceId = traceIdFrom(source)
  if (traceId !== undefined && labels['trace_id'] === undefined) labels['trace_id'] = traceId
  return cleanRecord(labels)
}

function adapterSummary(source: Record<string, unknown>): Record<string, unknown> {
  const data = recordValue(source['data']) ?? {}
  const summary: Record<string, unknown> = {}
  copyFirst(summary, 'traceId', source, data, ['traceId', 'trace_id'])
  if (source['type'] !== undefined) summary['eventType'] = source['type']
  for (const key of [
    'status',
    'latencyMs',
    'costUsd',
    'piiEntityTypes',
    'interceptorsFired',
    'model',
    'provider',
    'score',
    'suiteId',
    'totalCases',
    'passedCases',
    'failedCases',
    'passed',
    'bundleId',
  ]) {
    copyFirst(summary, key, source, data, [key])
  }
  return cleanRecord(summary)
}

function copyFirst(
  out: Record<string, unknown>,
  outKey: string,
  primary: Record<string, unknown>,
  secondary: Record<string, unknown>,
  keys: string[],
): void {
  for (const key of keys) {
    if (primary[key] !== undefined && primary[key] !== null) {
      out[outKey] = structuredClone(primary[key])
      return
    }
    if (secondary[key] !== undefined && secondary[key] !== null) {
      out[outKey] = structuredClone(secondary[key])
      return
    }
  }
}

function traceIdFrom(source: Record<string, unknown>): string | undefined {
  const data = recordValue(source['data']) ?? {}
  return asString(source['traceId']) ?? asString(source['trace_id']) ?? asString(data['traceId'])
}

function sanitizeMetadata(value: unknown): unknown {
  if (Array.isArray(value)) return value.filter((item) => item !== null && item !== undefined).map(sanitizeMetadata)
  if (value === null || typeof value !== 'object') return value
  const out: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (raw === null || raw === undefined) continue
    if (CONTENT_KEYS.has(key)) out[camelHashKey(key)] = hashValue(raw)
    else out[key] = sanitizeMetadata(raw)
  }
  return out
}

function camelHashKey(key: string): string {
  const camel = key.includes('_')
    ? key.replace(/_([a-z])/g, (_match, char: string) => char.toUpperCase())
    : key
  return `${camel}Hash`
}

function hashValue(value: unknown): string {
  const payload = typeof value === 'string' ? value : JSON.stringify(canonical(value))
  return createHash('sha256').update(payload).digest('hex')
}

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical)
  if (value === null || typeof value !== 'object') return value
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(value as Record<string, unknown>).sort()) {
    out[key] = canonical((value as Record<string, unknown>)[key])
  }
  return out
}

function cleanRecord(record: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(record)) {
    if (value !== undefined && value !== null) out[key] = value
  }
  return out
}

function adapterTags(labels: Record<string, unknown>, id: string): string[] {
  const tags = ['gavio', `integration:${id}`]
  for (const key of ['tenant', 'feature', 'environment', 'workflow']) {
    const value = asString(labels[key])
    if (value !== undefined) tags.push(`${key}:${value}`)
  }
  return tags
}

function traceHeaders(traceId: string, id: string): Record<string, string> {
  const headers: Record<string, string> = { 'x-gavio-integration': id }
  if (traceId !== '') headers['x-gavio-trace-id'] = traceId
  return headers
}

function prefixSummary(summary: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(summary)) {
    if (value !== undefined && value !== null) out[`gavio.${key}`] = value
  }
  return out
}

function prefixLabels(labels: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(labels)) {
    if (value !== undefined && value !== null) out[`gavio.label.${key}`] = value
  }
  return out
}

function defaultOperation(id: string): string {
  if (id === 'promptfoo') return 'gavio.eval'
  if (id === 'vercel-ai-sdk') return 'gavio.route'
  return 'gavio.request'
}

function recordValue(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined
}

function asString(value: unknown): string | undefined {
  return typeof value === 'string' && value !== '' ? value : undefined
}

function recipe(
  id: string,
  name: IntegrationRecipe['name'],
  category: IntegrationRecipe['category'],
  externalOwns: string[],
  gavioOwns: string[],
  gavioSurfaces: string[],
  recommendedExporters: Array<'jsonl' | 'otel'>,
  metadata: Record<string, string>,
): IntegrationRecipe {
  return {
    id,
    name,
    category,
    externalOwns,
    gavioOwns,
    gavioSurfaces,
    recommendedExporters,
    metadata,
    docsPath: `docs/integrations/${id}.md`,
    examplePath: `examples/integrations/${id}/recipe.py`,
  }
}

const INTEGRATIONS: IntegrationRecipe[] = [
  recipe(
    'litellm',
    'LiteLLM',
    'gateway',
    ['multi-provider proxy', 'virtual keys', 'provider routing', 'gateway rate and budget tiers'],
    [
      'app-level PII and policy checks before proxy calls',
      'metadata-only audit and runtime events',
      'tenant, feature, and workflow cost labels',
    ],
    ['metadata', 'runtime_events', 'audit_hashes', 'cost_governance', 'policy_packs'],
    ['jsonl', 'otel'],
    { gateway: 'litellm', integration: 'litellm', integration_kind: 'gateway' },
  ),
  recipe(
    'portkey',
    'Portkey',
    'gateway',
    ['AI gateway configuration', 'organization-level controls', 'provider routing', 'gateway logs'],
    ['embedded runtime policy decisions', 'pre/post interceptor facts', 'metadata-only audit trail'],
    ['metadata', 'runtime_events', 'audit_hashes', 'policy_packs', 'tool_runtime'],
    ['jsonl', 'otel'],
    { gateway: 'portkey', integration: 'portkey', integration_kind: 'gateway' },
  ),
  recipe(
    'helicone',
    'Helicone',
    'gateway_observability',
    ['LLM gateway analytics', 'request dashboard', 'prompt workflow analytics'],
    [
      'local runtime controls before and after provider calls',
      'privacy-preserving labels for correlation',
      'hash-only audit evidence',
    ],
    ['metadata', 'runtime_events', 'audit_hashes', 'cost_governance'],
    ['jsonl'],
    { gateway: 'helicone', integration: 'helicone', integration_kind: 'gateway_observability' },
  ),
  recipe(
    'langfuse',
    'Langfuse',
    'observability',
    ['LLM traces', 'prompt management', 'eval datasets', 'human review workflows'],
    [
      'metadata-safe runtime facts',
      'policy, PII, cost, and tool context',
      'audit hashes without raw content',
    ],
    ['metadata', 'runtime_events', 'audit_hashes', 'prompt_evals'],
    ['jsonl'],
    { integration: 'langfuse', integration_kind: 'observability' },
  ),
  recipe(
    'openlit',
    'OpenLIT',
    'observability',
    ['OpenTelemetry-native observability', 'fleet dashboards', 'APM correlation'],
    [
      'runtime event source',
      'privacy-preserving OTel span attributes',
      'interceptor decision events',
    ],
    ['metadata', 'runtime_events', 'otel_spans', 'cost_governance'],
    ['otel'],
    { integration: 'openlit', integration_kind: 'observability' },
  ),
  recipe(
    'promptfoo',
    'promptfoo',
    'eval',
    ['eval suites', 'red-team tests', 'CI pass/fail gates'],
    [
      'production-like runtime assertions',
      'PII, policy, cost, and tool outcome signals',
      'metadata-safe eval reports',
    ],
    ['metadata', 'runtime_events', 'prompt_evals', 'policy_packs', 'tool_runtime'],
    ['jsonl'],
    { integration: 'promptfoo', integration_kind: 'eval' },
  ),
  recipe(
    'langchain',
    'LangChain',
    'framework',
    ['chains', 'agents', 'tool orchestration', 'memory abstractions'],
    [
      'request runtime governance around model calls',
      'callback-exportable runtime metadata',
      'tool result validation before model re-entry',
    ],
    ['metadata', 'runtime_events', 'tool_runtime', 'audit_hashes'],
    ['jsonl', 'otel'],
    { framework: 'langchain', integration: 'langchain', integration_kind: 'framework' },
  ),
  recipe(
    'langgraph',
    'LangGraph',
    'framework',
    ['graph state', 'node execution', 'checkpointing', 'agent orchestration'],
    [
      'per-node runtime labels',
      'policy and audit context for model/tool nodes',
      'metadata-safe replay evidence',
    ],
    ['metadata', 'runtime_events', 'tool_runtime', 'audit_hashes'],
    ['jsonl', 'otel'],
    { framework: 'langgraph', integration: 'langgraph', integration_kind: 'framework' },
  ),
  recipe(
    'vercel-ai-sdk',
    'Vercel AI SDK',
    'framework',
    ['frontend streaming UX', 'server actions', 'provider convenience APIs'],
    [
      'server-side runtime governance before streaming starts',
      'metadata-only runtime export',
      'policy and cost labels for app routes',
    ],
    ['metadata', 'runtime_events', 'otel_spans', 'policy_packs'],
    ['jsonl', 'otel'],
    {
      framework: 'vercel-ai-sdk',
      integration: 'vercel-ai-sdk',
      integration_kind: 'framework',
    },
  ),
  recipe(
    'openai-sdk',
    'OpenAI SDK',
    'provider_sdk',
    ['provider-specific API surface', 'streaming primitives', 'file and assistant endpoints'],
    [
      'OpenAI-compatible chat shim for governed completions',
      'runtime policy checks around provider calls',
      'metadata-safe audit and export',
    ],
    ['metadata', 'runtime_events', 'audit_hashes', 'policy_packs'],
    ['jsonl', 'otel'],
    { provider_sdk: 'openai', integration: 'openai-sdk', integration_kind: 'provider_sdk' },
  ),
]
