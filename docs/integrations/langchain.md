# LangChain Integration

Since: `1.9.0`

Use LangChain for chains, agents, tool orchestration, and memory abstractions.
Use Gavio around model calls or callback boundaries to provide runtime
governance, audit hashes, metadata export, and tool result validation before
outputs re-enter model context.

```text
LangChain chain/agent -> Gavio runtime -> provider adapter
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "langchain",
    tenant="acme",
    feature="agent-research",
    environment="prod",
)
```

Use JSONL for callback workers or OTel spans when model calls should appear in
APM traces.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/langchain/recipe.py
```
