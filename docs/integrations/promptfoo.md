# promptfoo Integration

Since: `1.9.0`

Use promptfoo for eval suites, red-team tests, and CI gates. Use Gavio to add
production-like runtime assertions: PII type counts, policy outcomes, cost
limits, tool provenance, and metadata-safe eval reports.

```text
promptfoo case -> app test harness -> Gavio runtime -> provider/mock
```

```python
from gavio import integration_metadata

metadata = integration_metadata(
    "promptfoo",
    tenant="acme",
    feature="support-chat",
    environment="ci",
)
```

Export JSONL and assert over event metadata. Keep promptfoo responsible for test
case orchestration and Gavio responsible for production runtime signals.

Offline example:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/promptfoo/recipe.py
```
