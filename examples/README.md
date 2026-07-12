# Gavio examples

Runnable example projects in all three languages. Each row is the same scenario
implemented per SDK, so you can compare the APIs side by side.

| # | Scenario | Python | JavaScript | Java | Needs a key? |
|---|---|---|---|---|---|
| 01 | **Quickstart** — PII redact + restore, audit, cost (dev mode) | [py](./python/01-quickstart/) | [js](./javascript/01-quickstart/) | [java](./java/01-quickstart/) | no |
| 02 | **Production gateway** — audit → PII guard → timeout → retry | [py](./python/02-production-gateway/) | [js](./javascript/02-production-gateway/) | [java](./java/02-production-gateway/) | optional |
| 03 | **Custom scanner** — write a `PiiScanner` + test it | [py](./python/03-custom-scanner/) | [js](./javascript/03-custom-scanner/) | [java](./java/03-custom-scanner/) | no |
| 04 | **Production core stack** — hash-chain audit → PII → rate limit → guardrails → semantic cache | [py](./python/04-production-stack/) | — | — | no |
| 05 | **Inspector & multi-agent tracing** — trace waterfall, PII diff, agent DAG, sessions | [py](./python/05-inspector/) | [js](./javascript/05-inspector/) | — | no |
| 06 | **Policy Packs** — core PII + FinTech + custom regex pack | [py](./python/06-policy-packs/) | [js](./javascript/06-policy-packs/) | [java](./java/06-policy-packs/) | no |
| 07 | **Tool Runtime** — schema, freshness, conflicts, permissions, approvals, replay | [py](./python/07-tool-runtime/) | [js](./javascript/07-tool-runtime/) | [java](./java/07-tool-runtime/) | no |
| 08 | **Runtime Export** — metadata-safe runtime events and JSONL export | [py](./python/08-runtime-export/) | [js](./javascript/08-runtime-export/) | [java](./java/08-runtime-export/) | no |
| 09 | **Prompt Registry + Evals** — versioned templates and metadata-safe eval reports | [py](./python/09-prompt-registry-evals/) | [js](./javascript/09-prompt-registry-evals/) | [java](./java/09-prompt-registry-evals/) | no |
| 12 | **Domain Policy Pack Catalog** — signed domain packs, overrides, suppression | [py](./python/12-domain-policy-packs/) | [js](./javascript/12-domain-policy-packs/) | [java](./java/12-domain-policy-packs/) | no |
| 13 | **Self-hosted Control Plane** — runtime config, policy source, cached fallback | [py](./python/13-control-plane/) | [js](./javascript/13-control-plane/) | [java](./java/13-control-plane/) | no |

Only example 02 needs a key — it uses a real provider if `ANTHROPIC_API_KEY` or
`OPENAI_API_KEY` is set, and otherwise falls back to the mock provider so it
always runs. Everything else runs in dev mode with **no API key**.

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

All examples target the `1.7.0` package line.
