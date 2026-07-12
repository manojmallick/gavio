# Prompt Registry + Evals

> Feature IDs: `F-EVAL-01` (Prompt Registry) · `F-EVAL-02` (Evals) · `F-EVAL-03` (Eval Runner + CI Gates) · `F-EVAL-04` (Prompt Registry v2) · `F-EVAL-05` (Eval + Prompt Workflow) | Since: v1.4.0; runner since v2.1.0; registry v2 since v2.2.0; prompt workflow since v2.4.0

The Prompt Registry stores versioned chat templates and renders them into
provider-agnostic Gavio messages. Rendering attaches the existing
`PromptLineage` metadata (`templateId`, `templateVersion`, variables, RAG source
refs) so audits and eval reports can identify which prompt version produced a
call without storing raw rendered prompt text in lineage.

Eval suites run deterministic prompt cases against a supplied completion
function. Reports contain pass/fail, numeric scores, assertion details, and a
SHA-256 output hash; raw model output is intentionally not stored in the report.

## Prompt Registry v2

v2.2.0 adds file-backed prompt manifests for production prompt workflows. A
manifest contains semver prompt template versions, approval metadata, registry
metadata, and an optional deterministic `HMAC-SHA256` signature. SDKs can load
the manifest from disk, verify the signature, resolve selectors such as
`latest`, `^1.0.0`, `~1.1.0`, and `>=1.0.0 <2.0.0`, and diff prompt versions
without exposing raw prompt message content.

```json
{
  "schemaVersion": "gavio.prompt-registry.v2",
  "registryId": "support-prompts",
  "templates": [{
    "id": "support.reply",
    "version": "1.1.0",
    "messages": [
      { "role": "system", "content": "You are concise." },
      { "role": "user", "content": "Reply to {{ customer }} about {{ topic }}." }
    ],
    "requiredVariables": ["customer", "topic"],
    "approval": {
      "status": "approved",
      "approvedBy": "support-lead",
      "approvedAt": "2026-07-12T10:00:00Z",
      "reviewers": ["support"]
    }
  }],
  "signature": {
    "algorithm": "HMAC-SHA256",
    "keyId": "prompt-registry-prod",
    "value": "<64 hex chars>"
  }
}
```

```python
from gavio.prompts import PromptRegistry, verify_prompt_manifest_signature

secret = "registry-v2-test-secret"
registry = PromptRegistry.from_file("prompts.json", verify_secret=secret)
rendered = registry.render(
    "support.reply",
    {"customer": "Avery", "topic": "refund"},
    version="^1.0.0",
)
diff = registry.diff("support.reply", "1.0.0", "1.1.0")
```

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
      - run: pip install "gavio[yaml]>=3.0.0"
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

## Eval + Prompt Workflow

v2.4.0 links prompt versions to the eval suites that gate them. Links can live
at the manifest level, in template metadata, or in a runner suite file. Each
link can enforce a `failUnder` score, compare to a `baselineScore`, and allow a
bounded `maxRegression` for that exact prompt version. `gavio eval run` fails
when either the suite gate or any linked prompt gate fails.

```json
{
  "id": "support-regression",
  "templates": [{
    "id": "support.reply",
    "version": "1.1.0",
    "messages": [
      { "role": "system", "content": "You are concise." },
      { "role": "user", "content": "Reply to {{ customer }}." }
    ],
    "requiredVariables": ["customer"],
    "metadata": {
      "promptEvalLinks": [{
        "suiteId": "support-regression",
        "baselineScore": 1.0,
        "failUnder": 0.95,
        "maxRegression": 0.05
      }]
    }
  }],
  "cases": [{
    "id": "refund-leak",
    "templateId": "support.reply",
    "templateVersion": "1.1.0",
    "variables": { "customer": "Avery" },
    "output": "Avery, send your card number.",
    "assertions": [{ "type": "not_contains", "value": "card number" }],
    "triage": {
      "category": "safety",
      "severity": "high",
      "owner": "support-quality",
      "action": "revise_prompt"
    }
  }]
}
```

The SDK helpers expose the same workflow for release tooling:

```python
from gavio.prompts import (
    build_prompt_release_bundle,
    evaluate_prompt_workflow,
    prompt_eval_links_from_manifest,
)

links = prompt_eval_links_from_manifest(manifest)
workflow = evaluate_prompt_workflow(report, links)
bundle = build_prompt_release_bundle(
    manifest=manifest,
    prompt_id="support.reply",
    prompt_version="1.1.0",
    reports=[report],
    from_version="1.0.0",
)
```

Failure triage metadata is attached only to failed cases. Content-like metadata
keys such as `output`, `prompt`, `messages`, and `renderedPrompt` are replaced
with hashes, so JSON reports, JUnit reports, and prompt release bundles remain
metadata-safe.

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
- `spec/PromptManifest.schema.json` defines the signed v2 manifest shape.
- `spec/EvalReport.schema.json` defines the metadata-safe report shape.
- `test-vectors/prompts/registry-evals.json` verifies rendering, missing
  variables, lineage shape, passing/failing evals, scoring, and raw-output
  privacy across Python, JavaScript, and Java.
- `test-vectors/prompts/registry-v2.json` verifies signed manifest loading,
  semantic-version selection, approval metadata, metadata-safe prompt diffs,
  and deterministic signatures across Python, JavaScript, and Java.
- `test-vectors/prompts/workflow.json` verifies prompt-to-eval links,
  per-prompt regression gates, failure triage metadata, and prompt release
  bundles across Python, JavaScript, and Java.

## Examples

- [`examples/python/09-prompt-registry-evals`](../examples/python/09-prompt-registry-evals/)
  shows the minimal registry/render/eval flow.
- [`examples/python/21-eval-ci-gate`](../examples/python/21-eval-ci-gate/)
  shows a release-style prompt candidate gate: YAML suite input, baseline
  comparison, fail-under threshold, regression check, prompt-to-eval links,
  triage metadata, prompt release bundle evidence, and JSON/JUnit reports.
- [`examples/python/23-prompt-registry-v2`](../examples/python/23-prompt-registry-v2/)
  shows signed manifest loading, semver selectors, approval metadata, and
  metadata-safe prompt diffs.
