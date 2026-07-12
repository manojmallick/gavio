# Prompt Registry + Evals

> Feature IDs: `F-EVAL-01` (Prompt Registry) · `F-EVAL-02` (Evals) | Since: v1.4.0

The Prompt Registry stores versioned chat templates and renders them into
provider-agnostic Gavio messages. Rendering attaches the existing
`PromptLineage` metadata (`templateId`, `templateVersion`, variables, RAG source
refs) so audits and eval reports can identify which prompt version produced a
call without storing raw rendered prompt text in lineage.

Eval suites run deterministic prompt cases against a supplied completion
function. Reports contain pass/fail, numeric scores, assertion details, and a
SHA-256 output hash; raw model output is intentionally not stored in the report.

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

rendered = registry.render("support.reply", {"customer": "Avery", "topic": "refund"})

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
print(report.to_dict()["score"])
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

const rendered = registry.render('support.reply', { customer: 'Avery', topic: 'refund' })

const suite = new EvalSuite({
  id: 'support-smoke',
  cases: [{
    id: 'refund',
    templateId: 'support.reply',
    variables: { customer: 'Avery', topic: 'refund' },
    assertions: [{ type: 'contains', value: 'refund' }],
  }],
})

const report = await suite.run(registry, () => 'Avery refund approved')
console.log(report.score)
```

## Java

```java
import io.gavio.prompts.EvalAssertion;
import io.gavio.prompts.EvalCase;
import io.gavio.prompts.EvalSuite;
import io.gavio.prompts.PromptRegistry;
import io.gavio.prompts.PromptTemplate;
import io.gavio.types.Message;
import java.util.List;
import java.util.Map;

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

var report = suite.run(registry, (prompt, testCase) -> "Avery refund approved");
System.out.println(report.score());
```

## Shared Contract

- `spec/PromptTemplate.schema.json` defines the registered template shape.
- `spec/EvalReport.schema.json` defines the metadata-safe report shape.
- `test-vectors/prompts/registry-evals.json` verifies rendering, missing
  variables, lineage shape, passing/failing evals, scoring, and raw-output
  privacy across Python, JavaScript, and Java.
