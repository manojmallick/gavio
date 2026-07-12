# Integrations

Since: `1.9.0`

Use Gavio as the embedded runtime governance layer beside the tools teams
already use.

```text
Application
  -> Gavio embedded runtime
  -> gateway / provider / framework
  -> observability, eval, and security tools through runtime events
```

| Tool | Category | Let that tool own | Let Gavio own | Export |
|---|---|---|---|---|
| LiteLLM | gateway | Proxy, virtual keys, provider routing | App PII/policy, audit hashes, cost labels | JSONL, OTel |
| Portkey | gateway | Gateway config, org controls, routing | Embedded policy, interceptor facts, audit | JSONL, OTel |
| Helicone | gateway observability | Gateway analytics and prompt workflows | Local runtime controls and privacy-safe labels | JSONL |
| Langfuse | observability | Traces, prompts, eval datasets, review loops | Runtime facts and policy/audit context | JSONL |
| OpenLIT | observability | OTel-native dashboards and APM correlation | Runtime event source and span attributes | OTel |
| promptfoo | eval | Eval suites, red-team tests, CI gates | Runtime assertions and safe eval reports | JSONL |
| LangChain | framework | Chains, agents, tools, memory | Governed model calls and tool validation | JSONL, OTel |
| LangGraph | framework | Graph state, nodes, checkpoints | Per-node labels and replay evidence | JSONL, OTel |
| Vercel AI SDK | framework | Streaming UX, server actions, provider APIs | Server-side governance and route labels | JSONL, OTel |
| OpenAI SDK | provider SDK | Provider-specific APIs, files, assistants | Governed chat shim, policy, audit/export | JSONL, OTel |

Use the SDK catalog helpers to keep labels consistent:

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "litellm",
    tenant="acme",
    feature="support-chat",
    environment="prod",
)
```

```ts
import { integrationMetadata } from "gavio/integrations"

const metadata = integrationMetadata("openlit", {
  tenant: "acme",
  feature: "support-chat",
  environment: "prod",
})
```

```java
import io.gavio.integrations.IntegrationCatalog;

var metadata = IntegrationCatalog.metadata(
    "langchain",
    Map.of("tenant", "acme", "feature", "support-chat", "environment", "prod"));
```

These scalar labels flow into runtime events and cost dimensions without
exporting raw prompt or response text.

## Adapter payloads

Since: `2.5.0`

Adapter payload helpers build metadata-only fragments for ecosystem SDK calls,
configs, callbacks, or telemetry wrappers. They do not import external SDKs.

| Tool | Payload target |
|---|---|
| LiteLLM | completion kwargs metadata and trace headers |
| promptfoo | default test metadata, Gavio vars, runtime assertions |
| Langfuse | trace and generation metadata |
| OpenLIT | OTel/OpenLIT span attributes |
| LangChain | `RunnableConfig` metadata and tags |
| LangGraph | `RunnableConfig` metadata, tags, and configurable ids |
| Vercel AI SDK | request headers and experimental telemetry metadata |

All adapter payloads share this envelope:

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
replaced with SHA-256 hash fields. Runtime-event source content is not copied
into the adapter summary.

```python
from gavio import integration_adapter_payload

payload = integration_adapter_payload(
    "langfuse",
    {"traceId": "trace_123", "data": {"status": "ok", "provider": "openai"}},
    metadata={"tenant": "acme", "prompt": "raw text"},
)
```

```ts
import { integrationAdapterPayload } from "gavio/integrations"

const payload = integrationAdapterPayload(
  "vercel-ai-sdk",
  { traceId: "trace_123", data: { status: "ok", provider: "openai" } },
  { metadata: { tenant: "acme", prompt: "raw text" } },
)
```

```java
import io.gavio.integrations.IntegrationAdapters;

var payload = IntegrationAdapters.payload(
    "langchain",
    Map.of("traceId", "trace_123", "data", Map.of("status", "ok", "provider", "openai")),
    Map.of("tenant", "acme", "prompt", "raw text"));
```
