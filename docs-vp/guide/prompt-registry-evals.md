# Prompt Registry + Evals

> Since v1.4.0: `F-EVAL-01` and `F-EVAL-02`.

The Prompt Registry stores versioned chat templates and renders them into Gavio
messages with `PromptLineage` attached. Eval suites run deterministic cases
against a supplied completion function and return metadata-safe reports with
pass/fail, score, assertion details, and output hashes instead of raw model
output.

## Python

```python
from gavio import EvalSuite, PromptRegistry, PromptTemplate

registry = PromptRegistry([
    PromptTemplate(
        id="support.reply",
        version="2026-07-12",
        messages=[
            {"role": "system", "content": "You are concise."},
            {"role": "user", "content": "Reply to {{ customer }} about {{ topic }}."},
        ],
        required_variables=("customer", "topic"),
    )
])

suite = EvalSuite.from_dict({
    "id": "support-smoke",
    "cases": [{
        "id": "refund",
        "templateId": "support.reply",
        "variables": {"customer": "Avery", "topic": "refund"},
        "assertions": [{"type": "contains", "value": "refund"}],
    }],
})

report = await suite.run(registry, lambda _prompt, _case: "Avery refund approved")
```

## JavaScript

```typescript
import { EvalSuite, PromptRegistry, PromptTemplate } from 'gavio'

const registry = new PromptRegistry([
  new PromptTemplate({
    id: 'support.reply',
    version: '2026-07-12',
    messages: [
      { role: 'system', content: 'You are concise.' },
      { role: 'user', content: 'Reply to {{ customer }} about {{ topic }}.' },
    ],
    requiredVariables: ['customer', 'topic'],
  }),
])

const report = await new EvalSuite({
  id: 'support-smoke',
  cases: [{
    id: 'refund',
    templateId: 'support.reply',
    variables: { customer: 'Avery', topic: 'refund' },
    assertions: [{ type: 'contains', value: 'refund' }],
  }],
}).run(registry, () => 'Avery refund approved')
```

## Java

```java
PromptRegistry registry = new PromptRegistry();
registry.register(new PromptTemplate(
    "support.reply",
    "2026-07-12",
    List.of(
        Message.of("system", "You are concise."),
        Message.of("user", "Reply to {{ customer }} about {{ topic }}.")),
    List.of("customer", "topic"),
    Map.of()));

EvalSuite suite = new EvalSuite("support-smoke", List.of(new EvalCase(
    "refund",
    "support.reply",
    null,
    Map.of("customer", "Avery", "topic", "refund"),
    List.of(new EvalAssertion("contains", "refund", false)),
    Map.of())));

EvalReport report = suite.run(registry, (prompt, testCase) -> "Avery refund approved");
```

Shared vectors live in `test-vectors/prompts/registry-evals.json`, with schemas
in `spec/PromptTemplate.schema.json` and `spec/EvalReport.schema.json`.
