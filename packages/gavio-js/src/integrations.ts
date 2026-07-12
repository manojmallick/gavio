/** Dependency-light integration catalog helpers. */

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
