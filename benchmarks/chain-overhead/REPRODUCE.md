# Reproduce the chain-overhead benchmark

Everything below runs from a fresh clone and takes a few minutes. Each SDK needs
one command. Nothing is sent over the network except the usual dependency
downloads (`npm ci`, Maven), and no API keys are involved — the provider is a
local mock.

```bash
git clone https://github.com/manojmallick/gavio.git
cd gavio
```

## Prerequisites

| SDK | Needs |
|---|---|
| Python | Python 3.10+ (`python3 --version`; pick another interpreter with `PYTHON=python3.11`, see below) |
| JavaScript | Node 18+ and npm |
| Java | JDK 17+ and Maven 3.9+ (`java -version`, `mvn -version`) |

The Python SDK has no dependencies; nothing to `pip install`.

## One command per SDK

Run these from the repository root. Each builds what it needs, runs the
harness, prints the JSON run, and writes it to
`benchmarks/chain-overhead/out/<sdk>.json`. The exit status is non-zero if a CI
threshold is breached.

```bash
bash benchmarks/chain-overhead/run.sh python
bash benchmarks/chain-overhead/run.sh js      # runs npm ci && npm run build first if dist/ is missing
bash benchmarks/chain-overhead/run.sh java    # compiles the Java SDK modules it needs first
```

Use a specific interpreter with `PYTHON=python3.11 bash benchmarks/chain-overhead/run.sh python`.

## All three, combined

```bash
bash benchmarks/chain-overhead/run.sh all
```

This runs the three harnesses, merges them into **`benchmarks/latest.json`**,
prints a Markdown summary table, and validates the file. It is exactly what the
[Benchmarks workflow](../../.github/workflows/benchmarks.yml) runs in CI.

## What the wrapper does

If you would rather run the pieces yourself, these are the exact commands:

```bash
# Python
python3 benchmarks/chain-overhead/bench.py --out benchmarks/chain-overhead/out/python.json

# JavaScript
(cd packages/gavio-js && npm ci && npm run build)
node benchmarks/chain-overhead/bench.mjs --out benchmarks/chain-overhead/out/js.json

# Java (single-file source launch against the compiled SDK modules)
mvn -B -q -f packages/gavio-java/pom.xml -DskipTests \
  -pl gavio-core,gavio-interceptor-audit,gavio-interceptor-pii,gavio-interceptor-governance,gavio-interceptor-guardrails \
  -am compile
java -cp "$(printf '%s:' packages/gavio-java/gavio-{core,interceptor-audit,interceptor-pii,interceptor-governance,interceptor-guardrails}/target/classes | sed 's/:$//')" \
  benchmarks/chain-overhead/java/ChainOverheadBench.java --out benchmarks/chain-overhead/out/java.json

# Combine and validate
python3 benchmarks/chain-overhead/combine.py --markdown
python3 benchmarks/validate_latest.py
```

Every harness accepts `--seed`, `--warmup`, `--iterations` and `--out`. Keep the
defaults (seed 1337, 20 warm-up, 200 measured) for numbers comparable with
`latest.json`; other values are recorded in the run, and the validator only pins
the payload digest for the default seed and counts.

## Checking you got the same experiment

You cannot get the same latencies — those depend on your hardware — but you
should get the same *experiment*:

- `methodology.payloadDigest` is **identical across Python, JavaScript and Java
  and across machines** (it hashes the generated prompts). With the default seed
  it must equal `defaultPayloadDigest` in `spec.json`.
- Every audited configuration reports `checks.auditRecords` equal to the payload
  count (220), and the PII configurations report the same
  `checks.piiEntities` in every SDK.
- `python3 benchmarks/validate_latest.py` prints `valid`.

For a quieter, more representative run: plug a laptop into power, close other
applications, and run each SDK a few times. Compare *gaps* between
configurations; differences under ~50 µs are noise (see the
[README](README.md#reading-the-numbers)).

## Sharing your result

Validate a single SDK's run, then attach the file to an issue or pull request:

```bash
python3 benchmarks/validate_latest.py --run benchmarks/chain-overhead/out/python.json
```

The run already records your OS, architecture, CPU model, core count, memory and
runtime versions (never your hostname or username), so no further description is
needed. Have a look at the file before you share it.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Cannot find module .../dist/esm/index.js` | The JS SDK isn't built: `(cd packages/gavio-js && npm ci && npm run build)` |
| `package io.gavio... does not exist` from `java` | Compile the SDK first (the `mvn ... compile` step above) and launch from the repo root |
| A harness exits non-zero | A CI threshold was breached or a self-check failed; the JSON on stdout says which configuration (`"pass": false`) |
