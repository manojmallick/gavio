# Runtime Events

Since: `1.1.0` · Feature ID: `F-EXP-01`

Gavio runtime events are the public export surface for request execution. They
reuse the Inspector event envelope, so the Inspector, JSONL exporters, future
OpenTelemetry exporters, tests, and integration recipes all share one event
stream.

The default export mode is metadata-only. Runtime exporters remove
content-bearing fields before writing events:

- `messages`
- `content`
- `diff`

## Python

```python
from gavio import Gateway, JsonlRuntimeExporter

gw = (
    Gateway.builder()
    .dev_mode(True)
    .exporter(JsonlRuntimeExporter("runtime-events.jsonl"))
    .build()
)
```

## JavaScript

```ts
import { Gateway, jsonlRuntimeExporter } from 'gavio'

const gw = new Gateway({
  devMode: true,
  exporters: [jsonlRuntimeExporter({ path: 'runtime-events.jsonl' })],
})
```

## Java

```java
Gateway gateway = Gateway.builder()
    .devMode(true)
    .exporter(new JsonlRuntimeExporter(Path.of("runtime-events.jsonl")))
    .build();
```

Adding an exporter enables metadata-mode events and does not start the
Inspector HTTP server unless inspection is configured separately.

## Event Types

| Type | Purpose |
|---|---|
| `trace.start` | Request started |
| `interceptor.before.*` | Pre-call interceptor lifecycle |
| `provider.call.*` | Provider attempt lifecycle |
| `interceptor.after.*` | Post-call interceptor lifecycle |
| `governance.event` | Budget, drift, or policy signal |
| `trace.error` | Request failed |
| `trace.end` | Request completed |

The OpenTelemetry mapping is documented in
[Inspector](./inspector.md#opentelemetry-mapping).
