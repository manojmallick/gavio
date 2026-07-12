# OpenLIT Integration

Since: `1.9.0`

Use OpenLIT for OpenTelemetry-native observability, fleet dashboards, and APM
correlation. Use Gavio as the runtime event source and export OTel-style spans
with privacy-preserving attributes.

```text
App -> Gavio runtime -> OTel-style spans -> OpenLIT / collector
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "openlit",
    tenant="acme",
    feature="support-chat",
    environment="prod",
)
```

Prefer the OTel exporter. It maps `trace.start`, `provider.call.*`,
`interceptor.*`, and `governance.event` into spans and span events without raw
content.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/openlit/recipe.py
```
