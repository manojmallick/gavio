# Langfuse Integration

Since: `1.9.0`

Use Langfuse for traces, prompt management, eval datasets, and human review
loops. Use Gavio as the metadata-safe source of runtime facts: interceptor
mutations, PII detection, policy outcomes, retries, cost decisions, and tool
runtime checks.

```text
App -> Gavio runtime -> Provider/framework -> Langfuse trace ingest
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "langfuse",
    tenant="acme",
    feature="support-chat",
    environment="prod",
)
```

Export JSONL and transform it in your app or a small worker. Store Gavio hashes
and labels in Langfuse metadata, not raw prompt or response content.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/langfuse/recipe.py
```
