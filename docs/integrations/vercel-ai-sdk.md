# Vercel AI SDK Integration

Since: `1.9.0`

Use the Vercel AI SDK for frontend streaming UX, server actions, and provider
convenience APIs. Run Gavio server-side before streaming begins so policy,
cost, and metadata export happen inside the trusted runtime boundary.

```text
Route handler/server action -> Gavio runtime -> Vercel AI SDK/provider stream
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "vercel-ai-sdk",
    tenant="acme",
    feature="chat-route",
    environment="prod",
)
```

Use JSONL for route-level audit workers or OTel spans for APM traces. Avoid
putting raw prompt/response text in route logs.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/vercel-ai-sdk/recipe.py
```
