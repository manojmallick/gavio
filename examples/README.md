# Gavio examples

Runnable example projects in all three languages. Each row is the same scenario
implemented per SDK, so you can compare the APIs side by side.

| # | Scenario | Python | JavaScript | Java | Needs a key? |
|---|---|---|---|---|---|
| 01 | **Quickstart** — PII redact + restore, audit, cost (dev mode) | [py](./python/01-quickstart/) | [js](./javascript/01-quickstart/) | [java](./java/01-quickstart/) | no |
| 02 | **Production gateway** — audit → PII guard → timeout → retry | [py](./python/02-production-gateway/) | [js](./javascript/02-production-gateway/) | [java](./java/02-production-gateway/) | optional |
| 03 | **Custom scanner** — write a `PiiScanner` + test it | [py](./python/03-custom-scanner/) | [js](./javascript/03-custom-scanner/) | [java](./java/03-custom-scanner/) | no |

Examples 01 and 03 need **no API key** (dev mode). Example 02 uses a real
provider if `ANTHROPIC_API_KEY` or `OPENAI_API_KEY` is set, and otherwise falls
back to the mock provider so it always runs.

## Run them

**Python** (3.10+)
```bash
cd examples/python/01-quickstart
pip install -r requirements.txt      # installs gavio from PyPI
python quickstart.py
```

**JavaScript** (Node 18+) — plain JS, no TypeScript or build step. Gavio ships a
dual ESM + CJS build, so `import` **and** `require` both work.
```bash
cd examples/javascript/01-quickstart
npm install
node quickstart.mjs      # ESM
node quickstart.cjs      # CommonJS
```

**Java** (17+) — pulls `io.github.manojmallick:gavio-*` from Maven Central.
```bash
cd examples/java/01-quickstart
mvn -q compile exec:java
```

All examples are verified to run against the published `0.1.0` packages.
