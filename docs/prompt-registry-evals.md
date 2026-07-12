# Prompt Registry + Evals

> Feature IDs: `F-EVAL-01` (Prompt Registry) · `F-EVAL-02` (Evals) · `F-EVAL-03` (Eval Runner + CI Gates) | Since: v1.4.0; runner since v2.1.0

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

## Eval Runner + CI Gates

Python v2.1.0 adds `gavio eval run`, a local deterministic runner for
release checks. It accepts JSON or YAML suites, compares the candidate score to
a baseline report, enforces fail-under and regression thresholds, and writes
metadata-safe JSON plus JUnit XML for CI systems.

```yaml
id: support-release-gate
templates:
  - id: support.reply
    version: "2026-07-12-rc1"
    messages:
      - role: system
        content: You are concise. Never ask for secrets.
      - role: user
        content: Reply to {{ customer }} about {{ topic }}.
    requiredVariables:
      - customer
      - topic
cases:
  - id: refund-safe
    templateId: support.reply
    templateVersion: "2026-07-12-rc1"
    variables:
      customer: Avery
      topic: refund
    output: Avery, your refund is approved.
    assertions:
      - type: contains
        value: refund
      - type: not_contains
        value: card number
```

```bash
gavio eval run suite.yaml \
  --baseline baseline-report.json \
  --fail-under 0.95 \
  --max-regression 0.02 \
  --report reports/gavio-eval-report.json \
  --junit reports/gavio-eval-junit.xml \
  --summary
```

Suites may include inline templates or pass one or more external template files
with `--templates templates.yaml`. JSON suites work without optional
dependencies; install `gavio[yaml]` for the full YAML parser. The built-in
fallback parser handles the simple YAML shape shown above.

Use the same command in GitHub Actions:

```yaml
name: eval-gate
on: [pull_request]

jobs:
  gavio-evals:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - run: pip install "gavio[yaml]>=2.1.0"
      - run: mkdir -p reports
      - run: |
          gavio eval run examples/python/21-eval-ci-gate/suite.yaml \
            --baseline examples/python/21-eval-ci-gate/baseline-report.json \
            --fail-under 0.95 \
            --max-regression 0.02 \
            --report reports/gavio-eval-report.json \
            --junit reports/gavio-eval-junit.xml \
            --summary
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

## Examples

- [`examples/python/09-prompt-registry-evals`](../examples/python/09-prompt-registry-evals/)
  shows the minimal registry/render/eval flow.
- [`examples/python/21-eval-ci-gate`](../examples/python/21-eval-ci-gate/)
  shows a release-style prompt candidate gate: YAML suite input, baseline
  comparison, fail-under threshold, regression check, and JSON/JUnit reports.
