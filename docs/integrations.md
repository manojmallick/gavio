# Integrations

Since: `1.9.0`

Gavio should sit beside the tools teams already use. The recommended pattern is:

```text
Application
  -> Gavio embedded runtime
  -> gateway / provider / framework
  -> observability, eval, and security tools through runtime events
```

Gavio owns the in-process governance layer: PII, audit hashes, interceptor
decisions, cost attribution, policy packs, tool result checks, prompt/eval
signals, and runtime events. External tools can continue to own provider
routing, organization control planes, dashboards, prompt experiments, and
red-team workflows.

## Compatibility Matrix

| Tool | Category | Let that tool own | Let Gavio own | Gavio surfaces | Export |
|---|---|---|---|---|---|
| [LiteLLM](./integrations/litellm.md) | gateway | Proxy, virtual keys, provider routing, gateway budgets | App-level PII/policy, audit hashes, cost labels | metadata, runtime events, audit, cost, policy packs | JSONL, OTel |
| [Portkey](./integrations/portkey.md) | gateway | Gateway config, org controls, provider routing, gateway logs | Embedded policy decisions, interceptor facts, metadata-only audit | metadata, runtime events, audit, policy packs, tool runtime | JSONL, OTel |
| [Helicone](./integrations/helicone.md) | gateway observability | Gateway analytics, request dashboard, prompt workflows | Local runtime controls, privacy-safe labels, hash evidence | metadata, runtime events, audit, cost | JSONL |
| [Langfuse](./integrations/langfuse.md) | observability | Traces, prompt management, eval datasets, review loops | Metadata-safe runtime facts, policy/cost/tool context | metadata, runtime events, audit, evals | JSONL |
| [OpenLIT](./integrations/openlit.md) | observability | OTel-native observability, dashboards, APM correlation | Runtime event source, OTel span attributes, interceptor decisions | metadata, runtime events, OTel spans, cost | OTel |
| [promptfoo](./integrations/promptfoo.md) | eval | Eval suites, red-team tests, CI gates | Runtime assertions, PII/policy/cost/tool signals, safe reports | metadata, runtime events, evals, policy packs, tool runtime | JSONL |
| [LangChain](./integrations/langchain.md) | framework | Chains, agents, tools, memory abstractions | Governed model calls, callback-exportable metadata, tool validation | metadata, runtime events, tool runtime, audit | JSONL, OTel |
| [LangGraph](./integrations/langgraph.md) | framework | Graph state, node execution, checkpointing, orchestration | Per-node labels, model/tool policy context, replay evidence | metadata, runtime events, tool runtime, audit | JSONL, OTel |
| [Vercel AI SDK](./integrations/vercel-ai-sdk.md) | framework | Streaming UX, server actions, provider convenience APIs | Server-side governance, metadata export, route cost/policy labels | metadata, runtime events, OTel spans, policy packs | JSONL, OTel |
| [OpenAI SDK](./integrations/openai-sdk.md) | provider SDK | Provider-specific APIs, streaming, files, assistants | OpenAI-compatible governed completions, policy checks, audit/export | metadata, runtime events, audit, policy packs | JSONL, OTel |

The matrix is also available in SDKs:

```python
from gavio import compatibility_matrix, integration_metadata

metadata = integration_metadata(
    "litellm",
    tenant="acme",
    feature="support-chat",
    environment="prod",
)
rows = compatibility_matrix()
```

```ts
import { compatibilityMatrix, integrationMetadata } from "gavio/integrations"

const metadata = integrationMetadata("openlit", {
  tenant: "acme",
  feature: "support-chat",
  environment: "prod",
})
const rows = compatibilityMatrix()
```

```java
import io.gavio.integrations.IntegrationCatalog;

var metadata = IntegrationCatalog.metadata(
    "langchain",
    Map.of("tenant", "acme", "feature", "support-chat", "environment", "prod"));
var rows = IntegrationCatalog.compatibilityMatrix();
```

## Ecosystem Trust Package

Since: `2.7.0`

The Ecosystem Trust Package turns the integration catalog into executable
evidence. The shared conformance vector checks catalog coverage, docs and
example paths, metadata labels, adapter payload privacy, and sample production
apps. The generated matrix artifact lives at
[`docs/integrations/compatibility-matrix.json`](./integrations/compatibility-matrix.json)
and is checked by Python, JavaScript, and Java tests.

```bash
node scripts/gen-ecosystem-trust-matrix.mjs --check
```

The generator reads:

- [`test-vectors/integrations/catalog.json`](../test-vectors/integrations/catalog.json)
- [`test-vectors/integrations/adapters.json`](../test-vectors/integrations/adapters.json)
- [`test-vectors/integrations/ecosystem-trust.json`](../test-vectors/integrations/ecosystem-trust.json)

The checked-in matrix records a `conformance-tested` trust level for every
integration row, the metadata-only privacy boundary, adapter-payload coverage,
and the offline production app that exercises each integration.

## Adapter Payloads

Since: `2.5.0`

The catalog helpers describe where each tool fits. Adapter payload helpers build
metadata-only payload fragments you can pass into ecosystem SDK calls, configs,
callbacks, or telemetry wrappers. They do not import external SDKs.

| Tool | Helper | Payload target |
|---|---|---|
| LiteLLM | `litellm_adapter_payload` / `litellmAdapterPayload` / `IntegrationAdapters.litellm` | completion kwargs metadata and trace headers |
| promptfoo | `promptfoo_adapter_payload` / `promptfooAdapterPayload` / `IntegrationAdapters.promptfoo` | default test metadata, Gavio vars, runtime assertions |
| Langfuse | `langfuse_adapter_payload` / `langfuseAdapterPayload` / `IntegrationAdapters.langfuse` | trace and generation metadata |
| OpenLIT | `openlit_adapter_payload` / `openlitAdapterPayload` / `IntegrationAdapters.openlit` | OTel/OpenLIT span attributes |
| LangChain | `langchain_adapter_payload` / `langchainAdapterPayload` / `IntegrationAdapters.langchain` | `RunnableConfig` metadata and tags |
| LangGraph | `langgraph_adapter_payload` / `langgraphAdapterPayload` / `IntegrationAdapters.langgraph` | `RunnableConfig` metadata, tags, and configurable ids |
| Vercel AI SDK | `vercel_ai_sdk_adapter_payload` / `vercelAiSdkAdapterPayload` / `IntegrationAdapters.vercelAiSdk` | request headers and experimental telemetry metadata |

All adapter payloads share this outer envelope:

```json
{
  "schemaVersion": "gavio.integration-adapter.v1",
  "adapter": "langfuse",
  "target": "langfuse",
  "kind": "observability",
  "payload": {}
}
```

Content-bearing metadata fields such as `messages`, `content`, `diff`,
`prompt`, `response`, `output`, `renderedPrompt`, and `rendered_prompt` are
replaced with SHA-256 hash fields such as `promptHash`. Runtime-event source
content is not copied into the adapter summary.

```python
from gavio import integration_adapter_payload

event = {
    "traceId": "trace_123",
    "type": "trace.end",
    "data": {"status": "ok", "provider": "openai", "model": "gpt-4o-mini"},
}
payload = integration_adapter_payload(
    "langfuse",
    event,
    metadata={"tenant": "acme", "feature": "support-chat", "prompt": "raw text"},
)

langfuse_trace = payload["payload"]["trace"]
```

```ts
import { integrationAdapterPayload } from "gavio/integrations"

const payload = integrationAdapterPayload(
  "vercel-ai-sdk",
  {
    traceId: "trace_123",
    type: "trace.end",
    data: { status: "ok", provider: "openai", model: "gpt-4o-mini" },
  },
  { metadata: { tenant: "acme", feature: "support-chat", prompt: "raw text" } },
)

const telemetry = payload.payload.request
```

```java
import io.gavio.integrations.IntegrationAdapters;

var payload = IntegrationAdapters.payload(
    "langchain",
    Map.of(
        "traceId", "trace_123",
        "type", "trace.end",
        "data", Map.of("status", "ok", "provider", "openai", "model", "gpt-4o-mini")),
    Map.of("tenant", "acme", "feature", "support-chat", "prompt", "raw text"));

var runnableConfig = ((Map<?, ?>) payload.get("payload")).get("runnableConfig");
```

## Metadata Contract

Use these request metadata fields consistently across integrations:

| Field | Meaning |
|---|---|
| `tenant` | Customer or account scope |
| `feature` | Product feature or workflow |
| `user` | End-user or actor id, when safe to record |
| `environment` | `dev`, `staging`, `prod` |
| `workflow` | Longer-running workflow/session name |
| `gateway` | Gateway/proxy name, when present |
| `integration` | Integration id from the catalog |
| `integration_kind` | `gateway`, `observability`, `eval`, `framework`, or `provider_sdk` |
| `tool` | Tool name when a request represents tool summarization or validation |

The Inspector copies scalar values into runtime events and cost dimensions. The
runtime exporters write these labels without raw prompt or response content.

## Offline Examples

Every recipe has a runnable offline smoke example under
[`examples/integrations/`](../examples/integrations/). The full-stack example
covers gateway metadata, the Gavio runtime, OTel-style spans, eval assertions,
and audit replay evidence:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/full-stack/integration_stack.py
```

The v2.7.0 production-style trust apps cover the ecosystem matrix end to end:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/production-gateway-observability-eval/app.py
PYTHONPATH=packages/gavio-py python examples/integrations/production-agent-framework/app.py
```
