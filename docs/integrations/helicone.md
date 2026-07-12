# Helicone Integration

Since: `1.9.0`

Use Helicone for gateway observability, request analytics, and prompt workflow
analytics. Use Gavio before and after the model call to apply local runtime
controls and emit privacy-safe labels that can be correlated with gateway
records.

```text
App -> Gavio runtime -> Helicone gateway/provider endpoint -> Provider
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "helicone",
    tenant="acme",
    feature="sales-assistant",
    environment="prod",
)
```

JSONL export is the simplest bridge. Forward `traceId` or a request id header if
you want dashboard-to-audit correlation.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/helicone/recipe.py
```
