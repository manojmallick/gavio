# Inspector events → OpenTelemetry mapping

> Feature IDs: `F-OBS-07` (OTel export) · `F-DX-09` (Inspector core) | Since: v1.3.0

The Inspector's event catalogue (`spec/InspectorEvent.schema.json`) maps 1:1
onto OpenTelemetry-style spans. The OTel span exporter is a second subscriber on
the same `InspectorBus` the ring buffer reads — no separate instrumentation
path. This document is the canonical mapping for that exporter and for teams
correlating Gavio traces with their existing OTel pipelines.

The core SDKs emit dependency-light span JSON so OpenTelemetry packages remain
optional. The span shape is defined in
[`spec/GavioOtelSpan.schema.json`](../spec/GavioOtelSpan.schema.json), and
cross-SDK parity is enforced by
[`test-vectors/otel/spans.json`](../test-vectors/otel/spans.json).

## Exporters

```python
from gavio import Gateway, OtelSpanExporter

gw = Gateway.builder().exporter(OtelSpanExporter("otel-spans.jsonl")).build()
```

```typescript
import { Gateway, otelSpanExporter } from 'gavio'

const gw = new Gateway({
  exporters: [otelSpanExporter({ path: 'otel-spans.jsonl', serviceName: 'api' })],
})
```

```java
Gateway gateway = Gateway.builder()
    .exporter(new OtelSpanExporter(Path.of("otel-spans.jsonl"), "api"))
    .build();
```

```bash
gavio events convert --from runtime-events.jsonl --to otel-json --service-name api
```

## Span structure

| Inspector events | OTel span | Parent |
|---|---|---|
| `trace.start` … `trace.end` | Root span `gavio.request` | Remote parent from `parentTraceId` when present |
| `interceptor.before.start` … `interceptor.before.end` | Child span `gavio.interceptor.before {name}` | Root span |
| `provider.call.start` … `provider.call.end` | Child span `chat {model}` (one per attempt) | Root span |
| `interceptor.after.start` … `interceptor.after.end` | Child span `gavio.interceptor.after {name}` | Root span |
| `trace.error` | Span event `exception` on the root span + status `ERROR` | — |

Timestamps: `trace.start` carries the wall-clock anchor (`wallTimeUtc`); every
other event is offset from it by its monotonic `tNs`.

## Root span attributes (`gavio.request`)

| InspectorEvent field | OTel attribute |
|---|---|
| `traceId` | `gavio.trace_id` (also the span's trace correlation key) |
| `data.agentId` | `gavio.agent_id` |
| `data.sessionId` | `session.id` |
| `data.parentTraceId` | `gavio.parent_trace_id` |
| `data.provider` (trace.start) | `gen_ai.system` |
| `data.model` (trace.start) | `gen_ai.request.model` |
| `data.status` (trace.end) | span status (`ok` → `OK`, `error`/`blocked` → `ERROR`) |
| `data.latencyMs` (trace.end) | span duration |
| `data.costUsd` (trace.end) | `gen_ai.usage.cost` (USD) |
| `data.cacheHit` / `data.cacheType` (trace.end) | `gavio.cache.hit` / `gavio.cache.type` |
| `data.piiEntityTypes` (trace.end) | `gavio.pii.entity_types` (string array) |
| `data.interceptorsFired` (trace.end) | `gavio.interceptors` (string array) |

Content fields (`messages`, `content`, `diff`) are **never** exported —
they exist only in `full`/`redacted` capture modes and the exporter consumes
the metadata shape.

## Provider call span attributes (`chat {model}`)

Follows the OTel GenAI semantic conventions:

| InspectorEvent field | OTel attribute |
|---|---|
| `data.provider` | `gen_ai.system` |
| `data.model` | `gen_ai.request.model` |
| `data.modelVersion` (call.end) | `gen_ai.response.model` |
| `data.attempt` | `gavio.retry.attempt` |
| `data.usage.promptTokens` | `gen_ai.usage.input_tokens` |
| `data.usage.completionTokens` | `gen_ai.usage.output_tokens` |
| `data.status` / `data.errorType` | span status / `error.type` |
| `data.durationUs` | span duration |

## Interceptor span attributes

| InspectorEvent field | OTel attribute |
|---|---|
| `data.name` | `gavio.interceptor.name` |
| `data.mutated` | `gavio.interceptor.mutated` |
| `data.decision` (flattened) | `gavio.decision.*` |
| `data.durationUs` | span duration |

## Fleet metrics

For dashboard-level aggregates use the Prometheus metrics (`F-OBS-08`) rather
than deriving them from spans: `gavio_requests_total`, `gavio_tokens_total`,
`gavio_cost_usd_total`, `gavio_request_latency_ms` (histogram), and
`gavio_cache_hits_total`, all labelled by `provider` and `model`. A prebuilt
Grafana dashboard over these lives at
[`docs/grafana/gavio-dashboard.json`](./grafana/gavio-dashboard.json).

The Inspector (`gavio inspect --store …`) complements — not replaces — this
stack: PII redaction diffs, agent call graphs, semantic-cache near-misses,
and hash-chain verification are views a generic APM cannot render.

---

*Part of the Gavio project — gavio.io | MIT License*
