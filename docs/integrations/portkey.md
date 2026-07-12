# Portkey Integration

Since: `1.9.0`

Use Portkey for gateway configuration, organization controls, provider routing,
and gateway logs. Use Gavio for embedded pre/post decisions that only the
application can know: local PII, policy, tool result, and metadata-only audit
facts.

```text
App -> Gavio runtime -> Portkey gateway -> Provider
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "portkey",
    tenant="acme",
    feature="claims-review",
    environment="prod",
)
```

Send Gavio runtime events to JSONL or OTel and keep Portkey as the gateway
source of truth for routing and provider-level analytics.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/portkey/recipe.py
```
