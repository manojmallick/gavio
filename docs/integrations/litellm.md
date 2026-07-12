# LiteLLM Integration

Since: `1.9.0`

Use LiteLLM for proxy routing, virtual keys, provider failover, and gateway
budget tiers. Keep Gavio inside the application so PII, policies, audit hashes,
cost labels, and runtime events are captured before the request reaches the
proxy.

```text
App -> Gavio runtime -> LiteLLM proxy -> Provider
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "litellm",
    tenant="acme",
    feature="support-chat",
    environment="prod",
)
```

Export JSONL for lightweight workers or OTel-style spans for APM correlation.
Correlate LiteLLM request ids with Gavio `traceId` when your app forwards that
label as a request header.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/litellm/recipe.py
```
