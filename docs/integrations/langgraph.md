# LangGraph Integration

Since: `1.9.0`

Use LangGraph for graph state, node execution, checkpointing, and agent
orchestration. Use Gavio on model/tool nodes to attach per-node runtime labels,
policy outcomes, and replay evidence.

```text
LangGraph node -> Gavio runtime -> provider/tool result -> next node
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "langgraph",
    tenant="acme",
    feature="case-triage",
    workflow="review-graph",
    environment="prod",
)
```

Include `workflow` and graph/node labels in request metadata so Gavio events can
be joined back to graph runs without exporting raw content.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/langgraph/recipe.py
```
