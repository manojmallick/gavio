# Runtime Events

Feature ID: `F-EXP-01`
Since: `1.1.0`

Gavio runtime events are the public export surface for request execution. They
reuse the existing `InspectorEvent` envelope so the Inspector, JSONL exporters,
future OpenTelemetry exporters, tests, and integration recipes all share one
event stream.

The default export mode is metadata-only. Runtime exporters remove
content-bearing fields before writing events:

- `messages`
- `content`
- `diff`

This keeps trace IDs, timings, provider/model metadata, interceptor decisions,
costs, token usage, cache state, PII entity types, and governance signals while
avoiding raw prompt/response export by default.

## Event Envelope

The canonical schema is [`spec/GavioRuntimeEvent.schema.json`](../spec/GavioRuntimeEvent.schema.json),
which aliases the [`InspectorEvent`](../spec/InspectorEvent.schema.json) wire
format.

```json
{
  "schemaVersion": "1.0",
  "eventId": "018f...",
  "traceId": "018f...",
  "type": "provider.call.end",
  "tNs": 812341,
  "seq": 2,
  "data": {
    "durationUs": 812,
    "status": "ok",
    "attempt": 1,
    "modelVersion": "mock-1",
    "usage": {
      "promptTokens": 12,
      "completionTokens": 18,
      "totalTokens": 30
    },
    "costUsd": 0.0001
  }
}
```

## Event Types

| Type | Purpose |
|---|---|
| `trace.start` | Request started; provider, model, session, agent, parent trace, cost dimensions |
| `interceptor.before.start` | Pre-call interceptor entered |
| `interceptor.before.end` | Pre-call interceptor completed; includes mutation and decision metadata |
| `provider.call.start` | Provider attempt started |
| `provider.call.end` | Provider attempt completed or failed |
| `interceptor.after.start` | Post-call interceptor entered |
| `interceptor.after.end` | Post-call interceptor completed |
| `governance.event` | Mid-trace governance signal, such as budget or drift |
| `trace.error` | Request failed |
| `trace.end` | Request ended with status, cost, cache, PII entity types, and interceptor list |

## Python

```python
from gavio import Gateway, JsonlRuntimeExporter

gw = (
    Gateway.builder()
    .dev_mode(True)
    .exporter(JsonlRuntimeExporter("runtime-events.jsonl"))
    .build()
)

await gw.complete(messages=[{"role": "user", "content": "hello"}])
```

Adding an exporter enables metadata-mode runtime events and does not start the
Inspector HTTP server unless `.inspect(...)` is also configured.

## JavaScript

```typescript
import { Gateway, jsonlRuntimeExporter } from 'gavio'

const gw = new Gateway({
  devMode: true,
  exporters: [jsonlRuntimeExporter({ path: 'runtime-events.jsonl' })],
})

await gw.complete({ messages: [{ role: 'user', content: 'hello' }] })
```

## Java

```java
Gateway gateway = Gateway.builder()
    .devMode(true)
    .exporter(new JsonlRuntimeExporter(Path.of("runtime-events.jsonl")))
    .build();

gateway.complete(List.of(Message.of("user", "hello"))).join();
```

## Privacy Boundary

The JSONL exporters strip content-bearing keys even when the local Inspector is
configured in `full` mode. This lets a developer inspect content locally while
keeping exported event files metadata-only.

Use content capture only for local debugging or explicitly controlled internal
tooling. Runtime events are designed so production integrations do not need raw
prompt or response text.

## Relationship To OpenTelemetry

The event stream is the foundation for OpenTelemetry export. The current
mapping is documented in [OTel mapping](./otel-mapping.md). `1.1.0` ships the
runtime exporter contract and JSONL exporter; a later OTel exporter can subscribe
to the same stream without changing the Gateway pipeline.
