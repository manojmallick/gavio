# OpenAI SDK Integration

Since: `1.9.0`

Use the OpenAI SDK for provider-specific APIs such as streaming, files, and
assistant endpoints. Use Gavio for governed chat completions through its
OpenAI-compatible shim or provider adapter, plus policy checks, audit hashes,
and metadata-safe export.

```text
App -> Gavio runtime/OpenAI-compatible shim -> OpenAI SDK or direct adapter
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "openai-sdk",
    tenant="acme",
    feature="support-chat",
    environment="prod",
)
```

Keep OpenAI-specific endpoints in application code, and use Gavio when the call
needs common governance across providers.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/openai-sdk/recipe.py
```
