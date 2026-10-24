---
description: "Gavio roadmap v3.2 to v5.0: release train, feature IDs, and exit gates for tool calling, semantic eval, agents, EU compliance, ecosystem, and platform."
---

# Gavio roadmap: v3.2 → v5.0

Status: planning document · baseline `3.1.0` · planned 2026-10-18

Seven releases that turn Gavio from a broad runtime into the compliance-grade AI
runtime for regulated EU teams, with proof of real use at every step. Dates are
targets, not commitments; each release also has an adoption gate and ships only
when that gate holds.

**Strategy in one line:** win two categories outright (EU compliance depth and
verifiable trust architecture), stay credible on eval and agents, and stop
competing on provider count.

## Release train

| Version | Theme | Target | Headline outcome |
|---|---|---|---|
| `3.2` | Proof and polish | Dec 2026 | Real integrations, loop guard, normalised tool calls, full SDK example parity |
| `3.3` | Semantic eval | Feb 2027 | Tiered LLM-judge eval inside the eval runner |
| `3.4` | Agents | Mar 2027 | Task-level cost and success across multi-agent runs |
| `4.0` | EU Compliance Edition | May 2027 | Data residency plus article-level DORA, NIS2, GDPR and AI Act evidence |
| `4.1` | Ecosystem | Jul 2027 | Native framework middleware and enterprise providers |
| `4.2` | Hardening | Sep 2027 | Multimodal PII, streaming judges, adaptive limits, supply-chain evidence |
| `5.0` | Platform and community | Q4 2027 | Plugin SDK, outside maintainers, API freeze |

Feature IDs follow the `F-*` convention used in [`CHANGELOG.md`](https://github.com/manojmallick/gavio/blob/main/CHANGELOG.md); an ID is recorded there when its feature ships.

## Guiding principles

1. **Proof before breadth.** Every release has an adoption gate as well as a feature gate. If the adoption gate fails, the next release waits.
2. **Keep the design rules.** Zero mandatory dependencies, metadata-only audit, lockstep SDKs and shared test vectors (P1 to P8) apply to every new feature.
3. **Parity is part of done.** A feature is finished only when Python, JavaScript and Java all pass its test vectors and ship a runnable example. Parity means *library* parity: CLIs (`gavio eval run`, `gavio workflow release`, `gavio compliance export`) stay Python-only and are listed in the parity matrix.
4. **Compliance claims point to evidence.** Anything labelled DORA, NIS2, GDPR or AI Act links to a specific article and a generated artefact, never to marketing text. Mapping evidence helps a compliance team; it does not make anyone compliant.
5. **Measure in CI.** Overhead, judge accuracy and cost numbers are produced by CI jobs and copied into docs automatically.

**Order of work inside a feature:** `spec/` schema → shared `test-vectors/` →
Python reference → JavaScript → Java. A release is cut only when all three pass.

## Deliberately not building

| Not building | Reason |
|---|---|
| 100+ provider adapters | LiteLLM and Portkey already win here; Gavio adds the enterprise few and leaves the rest to the v5.0 plugin SDK |
| A hosted SaaS gateway | Breaks P6 (embeddable library) and the self-hosted compliance story |
| Its own agent framework | Gavio governs agents; building one would compete with the frameworks it integrates with |
| Vector database, fine-tuning, distillation | Outside runtime governance; at most, export redacted traces as training data |
| A general-purpose eval platform | Braintrust and Langfuse lead; Gavio's eval stays tied to CI gates and audit evidence |

## v3.2: Proof and polish

Credible to a stranger: real integrations, honest numbers, full SDK parity, and two runtime features developers feel on day one.

| ID | Feature | Done when |
|---|---|---|
| `F-DOC-10` | Docs truth sync: README tables, version map and benchmark numbers generated from `spec/`, an examples matrix and `benchmarks/latest.json` | A stale number fails CI |
| `F-DX-13` | Example parity: port the missing examples (04, 05, 15, 20 to 25) to the remaining SDKs, port the Python-only platform workflow release to JS and Java, add an example-matrix CI check | Every example runs in all three SDKs against the in-repo builds |
| `F-REL-09` | Loop guard interceptor keyed by root run: max steps, max cost, max wall time, repeated tool-call detection; on breach stop, fall back or dry-run log, recorded in the audit record | Shared vectors cover all four limits; demo stops a runaway agent at 25 steps or a cost cap |
| `F-ADP-03` | Normalised `ToolCall` / `ToolResult` model in `spec/`; adapters translate OpenAI, Anthropic, Gemini and Azure formats both ways, including parallel calls; tool arguments pass through PII and secret scanning | Same tool-calling code runs unchanged on four providers; vectors per mapping |
| `F-TRUST-02` | Reproducible interceptor-chain overhead benchmark with fixed seeds and `benchmarks/latest.json` | An outside rerun is linked from the README |
| `F-DOC-11` | `ADOPTERS.md` listing real users and how they use Gavio | At least three named entries, each with a one-line use |

**Exit gate:** three adopters listed, one external benchmark rerun, parity CI green.

**Progress (2026-10-20).** Releases `3.2.0` and `3.2.1` ship most of the slices, not all of
them: `F-ADP-03` in all three SDKs (spec, shared vectors and the Python reference in `3.2.0`;
the JavaScript and Java adapters in `3.2.1`), `F-REL-09` with its loop guard in all three SDKs
and its runaway-agent demo (example `26-loop-guard`) in `3.2.1`, and the `F-TRUST-02`
benchmark harness (the outside rerun is still pending). `F-DX-13` (more example ports) and
`F-DOC-11` are not done, and `F-DOC-10` is only started: CI now checks the versions in docs, the
spec schema list and the generated benchmark tables, but the Inspector overhead percentages are
still typed by hand. So the v3.2 exit gate above is not met yet.

## v3.3: Semantic eval

Adds a `judge` case type to the eval runner so suites can check meaning and
criteria, not just exact output, while most cases stay cheap. A case moves to
the next tier only when the current one is unsure.

1. **Deterministic:** schema, regex, hash, length, cost and latency limits. Free.
2. **Classifier:** a fixed answer space with a confidence score.
3. **Criteria:** a cheap LLM checks a list of rubric points and explains each verdict.
4. **Frontier:** a strong model, only for disputes or a sampled share of cases.

| ID | Feature | Done when |
|---|---|---|
| `F-EVAL-06` | `JudgeAdapter` interface beside `ProviderAdapter`, reusing the fallback chain | Interface in `spec/`, vectors in three SDKs |
| `F-EVAL-07` | Classifier judge with confidence threshold and `escalate / fail / pass_with_flag` | Unavailable backend falls back without failing the run |
| `F-EVAL-08` | Criteria judge: per-rubric-point verdicts with short reasons | Per-criterion results appear in JSON and JUnit reports |
| `F-EVAL-09` | Pairwise judge comparing prompt version N with N-1, order randomised | Regression gates accept pairwise results |
| `F-EVAL-10` | Batch collector grouping classifier questions into one call | Cost and time per suite drop; number published |
| `F-EVAL-11` | Judge cache keyed by prompt, response and rubric version | Rerunning an unchanged suite makes zero judge calls |
| `F-EVAL-12` | Versioned rubrics with labelled calibration sets, including deliberately bad answers | A rubric that passes known-bad answers fails CI |
| `F-EVAL-13` | Judge audit: every verdict written to the hash-chained audit record as metadata only | Verdicts verify with the existing chain verifier |

**Exit gate:** published judge agreement on the calibration sets and cost per 1,000 cases by tier, generated in CI from recorded fixtures.

## v3.4: Agents

Answers the question teams ask about agents: did the whole task succeed, and what did it cost end to end?

| ID | Feature | Done when |
|---|---|---|
| `F-AGT-01` | `TaskRecord` aggregating every call under one root run: cost, tokens, latency, tool calls, retries, outcome | Inspector and `gavio cost report` show per-task totals |
| `F-AGT-02` | Per-agent budgets inside a task using Cost Governance v2 decisions | Planner and coder agents hold separate caps in one run |
| `F-AGT-03` | Trajectory assertions (required, forbidden and ordered tools, max steps) checked against Tool Runtime replay records | Suites fail when an agent skips a required approval step |
| `F-AGT-04` | Optional judge on the final task result, linked to the task record | Task success rate reported per suite and prompt version |
| `F-TOOL-09` | MCP governance: PII and secret scanning on MCP arguments and results, per-server allow-lists, MCP calls in the audit chain | A tool call carrying an IBAN is redacted before it reaches an MCP server |

**Exit gate:** one real multi-agent project running on v3.4 with task records published as a case study.

## v4.0: EU Compliance Edition

Proves where data went and maps runtime evidence to specific regulatory
articles. Requests that cannot be routed in-region fail closed.

| ID | Feature | Done when |
|---|---|---|
| `F-RES-01` | Data residency policy: request-level `residency: eu` (or a region list); adapters declare endpoint regions explicitly; no match fails closed or logs a dry run | An EU-tagged request never reaches a non-EU endpoint in vectors; region recorded in the audit record |
| `F-RES-02` | Signed per-period residency evidence report | Report verifies with the Trust Package verifier |
| `F-COMP-01` | Versioned, signed control catalogue: DORA Art. 28 to 30, GDPR Art. 5, 25, 30 and 32, EU AI Act Art. 12 and 13, NIS2 Art. 21 | Each mapping names the control, the evidence artefact and its limits |
| `F-COMP-02` | `gavio compliance export --framework <name> --period <period>` evidence bundle | A reviewer gets one verifiable file per framework per period |
| `F-COMP-03` | Gap report on the Platform Runtime Profile, per article, not as one score | Gaps listed per article |
| `F-COMP-04` | Machine-readable provider register as input to a DORA register of information | Export matches what the router actually used |

**Before release:** an independent compliance or risk professional reviews the mappings and their feedback is recorded. **Exit gate:** external review done and one regulated-industry pilot.

## v4.1: Ecosystem

Replaces metadata-only adapter payloads with drop-in middleware and adds the providers regulated enterprises buy. Integrations live in optional packages so core stays dependency-free.

| ID | Feature | Done when |
|---|---|---|
| `F-INT-04` | LangChain / LangGraph middleware (Python and JS) | An existing app adopts Gavio by changing 2 to 3 lines |
| `F-INT-05` | Spring AI `ChatModel` decorator and Spring Boot auto-config (separate artifact) | Sample app with PII, audit and residency on |
| `F-INT-06` | Vercel AI SDK middleware and LlamaIndex callback | Samples in `examples/integrations` |
| `F-ADP-04` | AWS Bedrock adapter (stdlib SigV4, declared regions) | Vectors pass in three SDKs; EU region routing works |
| `F-ADP-05` | Google Vertex AI adapter | Same |
| `F-ADP-06` | Mistral (La Plateforme) adapter | Same |
| `F-ADP-07` | vLLM / TGI adapter with self-hosted health checks | Same |

**Exit gate:** each integration has a CI-tested sample app; time-to-first-request measured with three people new to Gavio.

## v4.2: Hardening

| ID | Feature | Done when |
|---|---|---|
| `F-SEC-11` | Multimodal PII: PDF input and region masking on top of the image support from `F-SEC-09` | A scanned ID or bank statement is masked in vectors; core stays dependency-free |
| `F-REL-10` | Streaming judges: incremental post-interceptors on a sliding window; early stop on a hard policy breach | A leaked IBAN stops the stream mid-response in a demo |
| `F-GOV-09` | Adaptive rate limiting from provider rate-limit headers and `retry-after` | Fewer 429s under a load test; numbers published |
| `F-TOOL-10` | Stateless MCP governance for servers behind load balancers | Works with two or more MCP server replicas in a sample |
| `F-TRUST-03` | Supply-chain hardening: SBOM per release, signed artifacts on all registries | Each release ships SBOM and signatures, verified in CI |

**Exit gate:** load test results and SBOM verification published with the release.

## v5.0: Platform and community

| ID | Feature | Done when |
|---|---|---|
| `F-PLUG-01` | Plugin SDK: versioned interfaces for providers, scanners, judges and policy packs, with a conformance kit built on the shared test vectors | A third party publishes a plugin without a core PR |
| `F-PLUG-02` | Signed plugin registry with conformance status | Registry lists plugins with conformance results |
| `F-DOC-12` | Contributor programme: good-first-issue labels, an owner per SDK and per policy pack, a public roadmap board | Two or more outside maintainers with merge rights on one area |
| `F-API-02` | Core API freeze: remove APIs deprecated in 3.x and 4.x (tracked in [`DEPRECATIONS.md`](https://github.com/manojmallick/gavio/blob/main/DEPRECATIONS.md)), publish a 5.x LTS policy | Migration guide plus scripts per SDK |

**Exit gate:** at least one release where an outside maintainer reviewed and merged a feature.

## ID corrections made while landing this roadmap

Three IDs in the original draft were already in use and were renumbered; all
other IDs are unchanged.

| Draft ID | Already used for | New ID |
|---|---|---|
| `F-REL-08` Loop guard | Model version pinning (`0.3.0`) | `F-REL-09` |
| `F-REL-09` Streaming judges | (follows the loop guard renumber) | `F-REL-10` |
| `F-GOV-07` Adaptive rate limiting | Drift detection (`0.5.0`) | `F-GOV-09` |
