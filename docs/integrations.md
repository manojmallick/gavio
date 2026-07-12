# Integrations

Since: `1.1.0`

Gavio should sit beside the tools teams already use. The recommended pattern is:

```text
Application
  -> Gavio embedded runtime
  -> gateway / provider / framework
  -> observability, eval, and security tools through runtime events
```

Gavio owns the in-process governance layer: PII, audit hashes, interceptor
decisions, cost attribution, policy packs, tool result checks, and runtime
events. External tools can continue to own provider routing, organization
control planes, dashboards, prompt experiments, and red-team workflows.

## Integration Roles

| Tool | Let that tool own | Let Gavio own |
|---|---|---|
| LiteLLM | Multi-provider proxy, virtual keys, provider routing, budget/rate tiers | App-level PII, audit hashes, policy packs, tool checks, per-feature cost labels |
| Portkey | AI gateway, org governance, prompt management, provider routing, gateway logs | Embedded runtime policy, metadata-only audit, pre/post interceptor decisions |
| Helicone | LLM gateway/observability dashboard, request analytics, prompt workflows | Local runtime controls before/after the gateway call |
| Langfuse | Traces, prompt management, eval datasets, human review loops | Metadata-safe event source and policy/audit context |
| OpenLIT | OpenTelemetry-native observability and fleet dashboards | Runtime event source and privacy-preserving labels |
| promptfoo | Evals, red-team tests, CI checks | Runtime assertions, policy outcomes, cost/PII regression signals |

## LiteLLM

Use LiteLLM as the proxy/provider router. Keep Gavio inside the app so the
request is labeled, checked, and audited before it reaches the proxy.

```text
App -> Gavio(PiiGuard, CostControl, Audit, Runtime Export) -> LiteLLM proxy -> Provider
```

Recommended Gavio metadata:

```json
{
  "tenant": "acme",
  "feature": "support-chat",
  "environment": "prod",
  "gateway": "litellm"
}
```

## Portkey

Use Portkey for gateway configuration, org-level controls, and provider routing.
Use Gavio for in-process policy decisions and metadata-only event export.

```text
App -> Gavio runtime -> Portkey gateway -> Provider
```

Recommended pattern:

- Gavio applies PII/policy/tool checks before the gateway call.
- Portkey records gateway-level request analytics and routing decisions.
- Gavio JSONL/runtime events preserve app-level decisions that a proxy cannot
  infer.

## Helicone

Helicone can receive the final provider request and provide dashboard analytics.
Gavio should run before and after that call to capture interceptor decisions and
audit metadata.

Recommended pattern:

- add `feature`, `tenant`, and `workflow` metadata to Gavio;
- export metadata-only runtime events to JSONL;
- correlate by `traceId` or a request header if the app forwards one.

## Langfuse

Langfuse is a strong destination for traces, prompt management, evals, and
review workflows. Gavio should be the local source of runtime facts:

- which interceptor mutated the request;
- whether PII was detected;
- which policy pack fired;
- what retry/fallback/cost decision happened;
- which tool result was stale or low-confidence.

For now, export JSONL and transform into the Langfuse ingestion shape in the
application or a small worker. A native exporter can build on the same
`GavioRuntimeEvent` contract later.

## OpenLIT

OpenLIT and other OpenTelemetry-native stacks should consume the same event
stream once an OTel exporter lands. Until then, use JSONL runtime export as a
metadata-safe bridge and follow the mapping in [OTel mapping](./otel-mapping.md).

Recommended future mapping:

- `trace.start` / `trace.end` -> root `gavio.request` span
- `provider.call.*` -> provider attempt span
- `interceptor.*` -> interceptor child spans
- `governance.event` -> span event

## promptfoo

Use promptfoo for evals and red-team workflows. Use Gavio runtime events to
assert production-like constraints:

- no exported PII entity types above an allowed set;
- no request blocked by policy;
- max cost under threshold;
- required tool provenance present;
- output schema or guardrail outcome acceptable.

In `1.1.0`, the bridge is JSONL. Later prompt/eval work can add direct
promptfoo export/import, but this release establishes the event facts needed for
that workflow.

## Minimal Metadata Contract

Use these request metadata fields consistently across integrations:

| Field | Meaning |
|---|---|
| `tenant` | Customer or account scope |
| `feature` | Product feature or workflow |
| `user` | End-user or actor id, when safe to record |
| `environment` | `dev`, `staging`, `prod` |
| `workflow` | Longer-running workflow/session name |
| `tool` | Tool name when a request represents tool summarization or validation |

The Inspector copies scalar values into `costDimensions`, and the runtime event
exporter writes them without raw prompt/response content.
