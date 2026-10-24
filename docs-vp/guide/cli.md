---
description: "Reference for the gavio command-line tools: inspect, cost report, events convert, policy, eval run and workflow release."
---

# Command-line reference

The Python package installs a `gavio` command. It works on files only: it never calls an LLM provider and needs no API key or network.

```bash
pip install gavio
gavio --help
```

| Command | What it does |
|---|---|
| [`gavio inspect`](#gavio-inspect) | Serve the read-only Inspector dashboard over a saved audit file |
| [`gavio cost report`](#gavio-cost-report) | Roll up spend, tokens, cache savings and PII counts from audit records |
| [`gavio events convert`](#gavio-events-convert) | Convert runtime-event JSONL into OpenTelemetry-style spans |
| [`gavio policy`](#gavio-policy) | List, verify and sign Policy Packs |
| [`gavio eval run`](#gavio-eval-run) | Run a prompt eval suite as a CI gate |
| [`gavio workflow release`](#gavio-workflow-release) | Build a platform workflow release artifact |

The Java and JavaScript SDKs have no `gavio` command; the files these commands read and write are the same across SDKs.

## Exit codes

All commands follow the same convention: `0` on success, `1` when the work failed (a bad or missing file, a failed gate, an invalid signature), and `2` for a command-line usage error (unknown option, missing argument). Errors are printed to stderr as `gavio <command>: <message>`.

## Making the input files

Several commands read files written by a running gateway. A minimal script that writes both an audit file and a runtime-event file (dev mode, no API key):

```python
import asyncio
from gavio import Gateway, JsonlRuntimeExporter
from gavio.interceptors.pii import PiiGuard
from gavio.interceptors.audit import AuditInterceptor

async def main():
    events = open("events.jsonl", "w")
    gw = (Gateway.builder().dev_mode(True)
          .use(PiiGuard())
          .use(AuditInterceptor(sink="jsonl://audit.jsonl"))   # one JSON record per call
          .exporter(JsonlRuntimeExporter(stream=events))       # one JSON event per line
          .build())
    for tenant in ("acme", "acme", "globex"):
        await gw.complete(
            messages=[{"role": "user", "content": "Email jan@example.com about billing"}],
            agent_id="demo",
            metadata={"costDimensions": {"tenant": tenant}},
        )
    events.close()

asyncio.run(main())
```

Audit records hold metadata and SHA-256 hashes, never the prompt or response text.

## `gavio inspect`

```bash
gavio inspect --store audit.jsonl [--port PORT] [--bind ADDRESS] [--token TOKEN]
```

Opens the [Inspector](inspector.md) in read-only **metadata mode** over an audit file written with the `jsonl://<path>` sink. It prints the number of traces it loaded and the address, then runs until you press Ctrl+C:

```
Gavio Inspector (metadata mode) — 3 traces from audit.jsonl
  http://127.0.0.1:7411/
```

| Option | Default | Meaning |
|---|---|---|
| `--store PATH` | required | The JSONL audit file |
| `--port PORT` | `7411` | Port to listen on |
| `--bind ADDRESS` | `127.0.0.1` | Address to listen on |
| `--token TOKEN` | none | Bearer token. **Required** for any address other than `127.0.0.1`, `localhost` or `::1` |

With a token set, requests need an `Authorization: Bearer <token>` header; without it the API answers `401`. A missing store file exits with `1`: `gavio inspect: [Errno 2] No such file or directory: 'missing.jsonl'`.

## `gavio cost report`

```bash
gavio cost report --audit audit.jsonl [--group-by KEY] [--since WHEN] \
                  [--budget-policy policy.json] [--usage-elapsed-ratio R] [--pretty]
```

Reads audit records and prints one JSON report on stdout. Use `--pretty` for indented output.

| Option | Meaning |
|---|---|
| `--audit PATH` | Required. JSONL audit or runtime summary file |
| `--group-by KEY` | Group the report. One of `agent_id`, `endpoint`, `environment`, `feature`, `middleware_chain`, `model`, `provider`, `session_id`, `tenant`, `tool`, `user`, `workflow` |
| `--since WHEN` | Only include records from this time on. An ISO 8601 timestamp **with a time zone**, for example `2026-10-01T00:00:00+00:00` or `2026-10-01T00:00:00Z`. Values such as `1h` are rejected, and a timestamp without a zone currently fails with a Python `TypeError` traceback |
| `--budget-policy PATH` | A budget policy JSON file: one policy object, a list, or `{"policies": [...]}`. Can be repeated |
| `--usage-elapsed-ratio R` | Fraction of the budget window already elapsed, for the forecast: forecast = spend so far ÷ R. Default `1.0` (no extrapolation) |

The report has `groups` (requests, cost, average cost, tokens, latency p50/p95/p99, cache hits and savings, errors, retry overhead, PII counts per type), `topSpend` and `total`. Example, grouped by model:

```bash
gavio cost report --audit audit.jsonl --group-by model --pretty
```

```json
{
  "groups": {
    "mock": {
      "cacheHits": 0,
      "costUsd": 0.0,
      "piiDetections": { "EMAIL": 3 },
      "requests": 3,
      "tokens": { "completion": 30, "prompt": 21, "total": 51 }
    }
  }
}
```

(abridged; the real report has more fields). Dev mode uses a mock provider, so cost is `0.0`.

With `--budget-policy` the report also has `budgets`, one entry per policy with `currentSpendUsd`, `forecastWindowSpendUsd`, `limitUsd`, `remainingUsd`, `scope`, `window` and a `status` (`ok` in the test run). A policy file is, for example:

```json
{ "id": "agent-demo", "scope_type": "agent", "scope_value": "demo", "window": "monthly", "limit_usd": 50 }
```

> **Check your dimensions.** Grouping by `tenant`, `feature`, `user`, `endpoint`, `environment`, `workflow` or `tool` only works if the records carry those values. Records written by the `jsonl://` audit sink in the Python SDK did not include them in testing, so `--group-by tenant` showed everything under `unknown`. Group by `agent_id`, `model`, `provider` or `session_id` for audit-sink files, or feed the command records that contain a `cost_dimensions` object.

An unknown `--group-by` value lists the valid keys and exits with `1`.

## `gavio events convert`

```bash
gavio events convert --from events.jsonl --to otel-json [--service-name NAME]
```

Reads runtime events (written by `JsonlRuntimeExporter`) and prints one OpenTelemetry-style span per line on stdout. `otel-json` is the only output format. `--service-name` sets the `service.name` attribute. Redirect the output to a file to feed your tracing pipeline. See [OTel mapping](https://github.com/manojmallick/gavio/blob/main/docs/otel-mapping.md) and [runtime events](runtime-events.md).

```bash
gavio events convert --from events.jsonl --to otel-json --service-name checkout > spans.jsonl
```

## `gavio policy`

```bash
gavio policy list
gavio policy validate PATH_OR_NAME
gavio policy sign PATH_OR_NAME
```

Works on [Policy Packs](interceptors.md): the built-in catalog or your own pack file.

- `list` prints the catalog: `code-security`, `core`, `finance`, `healthcare`, `hr`, `legal`, `regional/eu`, `regional/india`, `regional/us`, `support`.
- `validate` loads the pack and checks its manifest signature. Success prints `ok gavio.finance 1.0.0`; a bad signature or an unknown name prints an error and exits with `1` (`gavio policy validate: unknown policy pack: nope`).
- `sign` prints the SHA-256 signature value to put in the pack's manifest.

`PATH_OR_NAME` is a file path if one exists, otherwise a catalog name. Run `validate` in CI to catch hand-edited packs.

## `gavio eval run`

```bash
gavio eval run SUITE [--templates PATH]... [--baseline REPORT.json] \
    [--fail-under SCORE] [--max-regression DELTA] \
    [--report out.json] [--junit out.xml] [--summary] [--pretty]
```

Runs a deterministic eval suite (JSON or YAML) and acts as a CI gate. No provider call is made: cases use fixed outputs.

| Option | Meaning |
|---|---|
| `SUITE` | The suite file |
| `--templates PATH` | Extra prompt template files. Can be repeated |
| `--baseline PATH` | A previous JSON report to compare with |
| `--fail-under SCORE` | Fail when the score is below this value (0 to 1) |
| `--max-regression DELTA` | Fail when the score drops more than this against the baseline (default `0`) |
| `--report PATH` / `--junit PATH` | Write a JSON or JUnit XML report |
| `--summary` | Print a compact summary instead of the full report |

Tested with the bundled example (`examples/python/21-eval-ci-gate`):

```bash
gavio eval run suite.yaml --baseline baseline-report.json \
    --fail-under 1.0 --max-regression 0.0 --report eval-report.json --junit eval-junit.xml --summary --pretty
```

```json
{
  "failedCases": 0,
  "gate": { "baselineScore": 0.66666667, "failUnder": 1.0, "maxRegression": 0.0, "passed": true, "reasons": [], "scoreDelta": 0.33333333 },
  "passedCases": 2,
  "score": 1.0,
  "suiteId": "support-release-gate"
}
```

(abridged). The exit code is `0` when the gate passes and non-zero when it fails, so it works directly as a CI step. Details: [Prompt Registry + Evals](prompt-registry-evals.md).

## `gavio workflow release`

```bash
gavio workflow release MANIFEST [--output release.json] [--pretty] [--allow-failures]
```

Builds one metadata-only release artifact from a workflow manifest that points at prompt, eval, policy, trust-bundle and runtime-profile files. It prints the artifact (and writes it with `--output`) and exits `0` when every gate passed, `1` otherwise. `--allow-failures` returns `0` even when gates fail, for report-only runs.

The manifest refers to other files, and they must exist. In the bundled example (`examples/python/25-platform-workflow-release`) `workflow.json` needs `trust.json` and `profile.json`, which `platform_workflow_release.py` generates, so run the script first:

```bash
python platform_workflow_release.py
gavio workflow release workflow.json --output workflow-release.json --pretty
```

Without that first step you get `gavio workflow release: [Errno 2] No such file or directory: 'trust.json'`. See [Platform Workflow Release](platform-workflow-release.md).
