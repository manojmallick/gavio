# 08 - Runtime Export (Java)

Shows the v1.1.0 runtime event exporter. The example runs in dev mode, exports
metadata-only JSONL events to an in-memory writer, and verifies that no
content-bearing fields are exported.

```bash
mvn -q compile exec:java
```

See also: [Runtime events](../../../docs/runtime-events.md) and
[Integrations](../../../docs/integrations.md).
