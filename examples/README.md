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
| 14 | **Production Trust Package** — metadata-only release evidence bundle and verifier | [py](./python/14-production-trust/) | [js](./javascript/14-production-trust/) | [java](./java/14-production-trust/) | no |
| 15 | **Ecosystem integrations** — compatibility matrix, metadata labels, adapter payloads, generated trust matrix, sample production apps | [py](./integrations/) | — | — | no |
| 20 | **Platform Runtime Profile** — metadata-only production readiness profile and deterministic gaps | [py](./python/20-platform-runtime/) | — | — | no |
| 21 | **Eval CI Gate** — `gavio eval run`, prompt/eval links, baseline comparison, JSON/JUnit reports | [py](./python/21-eval-ci-gate/) | — | — | no |
| 22 | **Platform Feature Tour** — all major v2.x surfaces in one offline project | [py](./python/22-platform-feature-tour/) | — | — | no |
| 23 | **Prompt Registry v2** — signed manifests, semver selectors, approvals, metadata-safe diffs | [py](./python/23-prompt-registry-v2/) | — | — | no |
| 24 | **Enterprise Admin v2** — scoped admin keys, rollout approvals, audit export, retention controls | — | [js](./javascript/24-enterprise-admin-v2/) | — | no |
| 25 | **Platform Workflow Release** — unified prompt/eval/policy/trust/runtime-profile release artifact | [py](./python/25-platform-workflow-release/) | — | — | no |

Only example 02 needs a key — it uses a real provider if `ANTHROPIC_API_KEY` or
`OPENAI_API_KEY` is set, and otherwise falls back to the mock provider so it
always runs. Everything else runs in dev mode with **no API key**.

## Feature coverage

Use `22-platform-feature-tour` when you want one offline project that touches
the major v2.x runtime surfaces. Use the focused examples when you want the
smallest runnable project for one feature family.

| Feature group | Focused example | Umbrella example |
|---|---|---|
| Privacy, secrets, prompt injection, policy packs | `01`, `03`, `06`, `12` | `22` |
| Reliability, timeout, retry, guardrails, cache | `02`, `04` | `22` |
| Cost governance, runtime labels, budget reporting | `04`, `08`, `13` | `22` |
| Runtime events, JSONL export, OTel spans, metrics | `05`, `08`, `15` | `22` |
| Prompt registry, eval reports, CI-style gates, release bundles | `09`, `21`, `23` | `22` |
| Platform workflow releases across prompts, evals, policies, trust, and runtime profiles | `25` | — |
| Tool runtime, permissions, approvals, MCP metadata | `07` | `22` |
| Control plane, enterprise admin, trust bundle, platform profile | `13`, `14`, `20`, `24` | `22` |
| Ecosystem integration metadata, adapter payloads, generated trust evidence, and sample production apps | `15` | `22` |

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

Most language-specific package examples target the `3.0.0` package line. The
integration trust examples run from this repository branch and the current
package line once released.
