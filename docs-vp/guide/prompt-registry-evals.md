# Prompt Registry + Evals

> Since v1.4.0: `F-EVAL-01` and `F-EVAL-02`. Since v2.1.0: `F-EVAL-03`.
> Since v2.2.0: `F-EVAL-04`.

The Prompt Registry stores versioned chat templates and renders them into Gavio
messages with `PromptLineage` attached. Eval suites run deterministic cases
against a supplied completion function and return metadata-safe reports with
pass/fail, score, assertion details, and output hashes instead of raw model
output.

## Prompt Registry v2

v2.2.0 adds file-backed prompt manifests with semantic versions, approval
metadata, metadata-safe prompt diffs, and deterministic `HMAC-SHA256`
signatures.

```python
from gavio.prompts import PromptRegistry

registry = PromptRegistry.from_file("prompts.json", verify_secret="registry-v2-test-secret")
rendered = registry.render(
    "support.reply",
    {"customer": "Avery", "topic": "refund"},
    version="^1.0.0",
)
diff = registry.diff("support.reply", "1.0.0", "1.1.0")
```

Semver selectors include `latest`, exact versions, `^1.0.0`, `~1.1.0`, and
compound ranges such as `>=1.0.0 <2.0.0`. Prompt diffs hash message content
instead of returning raw prompt text.

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

## Eval Runner + CI Gates

Python v2.1.0 adds `gavio eval run`, a local deterministic runner for release
checks. It accepts JSON or YAML suites, compares candidate score to a baseline
report, enforces fail-under and regression thresholds, and writes
metadata-safe JSON plus JUnit XML for CI.

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

Suites may include inline templates or pass external template files with
`--templates templates.yaml`. JSON suites work without optional dependencies;
install `gavio[yaml]` for the full YAML parser. The built-in fallback parser
handles the simple YAML shape shown above.

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
      - run: pip install "gavio[yaml]>=2.3.0"
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

Shared vectors live in `test-vectors/prompts/registry-evals.json` and
`test-vectors/prompts/registry-v2.json`, with schemas in
`spec/PromptTemplate.schema.json`, `spec/PromptManifest.schema.json`, and
`spec/EvalReport.schema.json`.

## Examples

- `examples/python/09-prompt-registry-evals` shows the minimal
  registry/render/eval flow.
- `examples/python/21-eval-ci-gate` shows a release-style prompt candidate
  gate: YAML suite input, baseline comparison, fail-under threshold,
  regression check, and JSON/JUnit reports.
- `examples/python/23-prompt-registry-v2` shows signed manifest loading,
  semver selectors, approval metadata, and metadata-safe prompt diffs.
