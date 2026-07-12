# Changelog

All notable changes to Gavio are documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Feature IDs (e.g. `F-SEC-01`) group related changes across the three SDKs.

---

## [Unreleased]

Nothing yet.

---

## [2.5.0] — 2026-07-12

### Summary
**Ecosystem Adapters.** v2.5.0 adds dependency-light adapter payload helpers
for LiteLLM, promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and the
Vercel AI SDK across Python, JavaScript, and Java. Payloads propagate
trace/cost/model labels, replace content-bearing metadata with SHA-256 hashes,
and share a cross-SDK adapter vector plus schema coverage. Feature ID
`F-INT-02`. Feature PR #94 (#93).

Tests: Python 311 · JavaScript typecheck/build/309 tests green · Java modules
green · docs build green · stable release gate green · adapter examples green.
Inspector benchmarks were not rerun locally because this release changes
ecosystem adapter payload helpers/docs/examples, not Inspector runtime overhead.

### Added
- **Ecosystem Adapters (all SDKs, `F-INT-02`)** — added dependency-light
  adapter payload helpers for LiteLLM, promptfoo, Langfuse, OpenLIT, LangChain,
  LangGraph, and the Vercel AI SDK.
- **Adapter contract coverage** — added `IntegrationAdapterPayload` schema
  coverage plus a shared adapter vector that checks payload shape, trace
  propagation, metadata hashing, and raw-content omission.
- **Adapter examples and docs** — refreshed integration docs, package docs, and
  offline examples with metadata-only adapter payload usage.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, package/runtime versions, and
  integration docs to the `2.5.0` release line while keeping historical feature
  "since" labels intact.

---

## [2.4.0] — 2026-07-12

### Summary
**Eval + Prompt Workflow.** v2.4.0 links prompt registry versions to the eval
suites that gate them. The workflow records prompt-to-eval links, enforces
per-version regression gates, adds failure triage metadata without storing raw
prompt or output text, and builds prompt release bundles for release evidence.
Feature ID `F-EVAL-05`. Feature PR #91 (#90).

Tests: Python 310 · JavaScript typecheck/build/308 tests green · Java modules
green · control-plane app green · docs build green · stable release gate green ·
eval CI example green. Inspector benchmarks were not rerun locally because this
release changes prompt/eval workflow metadata and examples, not Inspector
runtime overhead.

### Added
- **Eval + Prompt Workflow (all SDKs, `F-EVAL-05`)** — added prompt/eval link
  contracts, per-prompt-version regression gates, metadata-safe failure triage,
  prompt release bundles, shared workflow vectors, and SDK helpers for release
  tooling.
- **Eval runner workflow integration (Python)** — `gavio eval run` now
  discovers prompt/eval links from suite and template metadata, fails linked
  prompt gates, and includes workflow, gate, and triage metadata in JSON/JUnit
  reports.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, package/runtime versions, and
  prompt/eval workflow docs to the `2.4.0` release line while keeping
  historical feature "since" labels intact.

---

## [2.3.0] — 2026-07-12

### Summary
**Control Plane Persistence.** v2.3.0 adds durable storage options for the
self-hosted control-plane app. The default JSON file store remains available,
while SQLite adds migration-backed local/private durability and Postgres adds
the managed database adapter path. Projects, environments, runtime keys, teams,
policies, policy rollouts, budgets, runtime events, audit records, and config
snapshots now survive process restarts when SQL storage is enabled. Feature ID
`F-CP-01`. Feature PR #89 (#88).

Tests: control-plane app 6 · Python 307 · JavaScript typecheck/build/306 tests
green · Java modules green · docs build green · stable release gate green.
Inspector benchmarks were not rerun because this release changes
control-plane app persistence/docs, not Inspector runtime overhead.

### Added
- **Control Plane Persistence (app, `F-CP-01`)** — added storage mode selection
  for JSON file, SQLite, and Postgres; idempotent SQL migrations tracked in
  `gavio_control_plane_migrations`; durable runtime/admin records; storage
  visibility in `/health`; CLI environment wiring; and CI coverage for the
  control-plane app on Node 22.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, package/runtime versions, and
  control-plane docs to the `2.3.0` release line while keeping historical
  feature "since" labels intact.

---

## [2.2.0] — 2026-07-12

### Summary
**Prompt Registry v2.** v2.2.0 adds a file-backed prompt registry workflow
across Python, JavaScript, and Java. SDKs can load signed prompt manifests,
resolve semantic-version selectors, carry approval metadata, and produce
metadata-safe prompt diffs that hash message content instead of exposing raw
prompt text. Feature ID `F-EVAL-04`. Feature PR #87 (#86).

Tests: Python 307 · JavaScript typecheck/build/306 tests green · Java modules
green · docs build green · stable release gate green · Prompt Registry v2
example smoke green. Inspector benchmarks were not rerun because this release
changes prompt registry manifests/docs and example coverage, not Inspector
runtime overhead.

### Added
- **Prompt Registry v2 (all SDKs, `F-EVAL-04`)** — added file-backed prompt
  manifests with semantic-version template selection, approval metadata,
  metadata-safe prompt diffs, deterministic HMAC-SHA256 manifest signatures,
  shared v2 vectors, schema coverage, docs, and an offline Python example.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site pages, stable gate fixtures, package/runtime versions, and
  trust/platform examples to the `2.2.0` release line while keeping historical
  feature "since" labels intact.

---

## [2.1.0] — 2026-07-12

### Summary
**Eval Runner + CI Gates.** v2.1.0 adds a Python `gavio eval run` CLI for
deterministic prompt eval release checks. The runner loads JSON or YAML suites,
supports inline or external prompt templates, compares candidate scores against
baseline reports, enforces fail-under and max-regression gates, and writes
metadata-safe JSON plus JUnit XML reports for CI. Feature ID `F-EVAL-03`.
Feature PR #85 (#84).

Tests: Python 303 · JavaScript typecheck/build/303 tests green · Java modules
green · docs build green · stable release gate green · eval CLI example green ·
platform feature tour smoke green. Inspector benchmarks were not rerun because
this release changes eval runner CLI/docs and example coverage, not Inspector
runtime overhead.

### Added
- **Eval runner CLI (Python, `F-EVAL-03`)** — added `gavio eval run` with
  JSON/YAML suite loading, `--templates`, `--fail-under`, `--baseline`,
  `--max-regression`, `--report`, `--junit`, `--pretty`, and `--summary`.
- **File-backed eval runner API** — added `gavio.prompts.runner` helpers for
  `EvalGate`, `EvalRunResult`, deterministic suite loading, gate evaluation,
  metadata-safe JSON report writing, and JUnit XML output.
- **YAML suite support** — added optional `gavio[yaml]`/`PyYAML>=6.0` while
  keeping JSON dependency-free and providing a small fallback parser for simple
  eval-suite YAML files.
- **Eval CI examples and docs** — added `examples/python/21-eval-ci-gate` with
  a YAML suite and baseline report, expanded Prompt Registry + Evals docs with
  GitHub Actions guidance, and added a broader offline platform feature tour.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, package/runtime versions, and
  example manifests to the `2.1.0` release line while keeping historical
  feature "since" labels intact.

---

## [2.0.0] — 2026-07-12

### Summary
**Platform-Grade Runtime.** v2.0.0 adds a metadata-only Platform Runtime
Profile across Python, JavaScript, and Java. The profile summarizes production
runtime posture with deterministic readiness scoring, platform surface checks,
privacy/content-key validation, profile hashing, and cross-SDK conformance via
a shared schema and test vector. Feature ID `F-PLAT-01`. Feature PR #83 (#82).

Tests: Python 299 · JavaScript 303 · Java modules green · docs build green ·
stable release gate green · platform example smoke green · Inspector
benchmarks green (Python 2.54%/5.05%, JavaScript 0.51%/0.64%, Java
1.27%/0.07% metadata/full p50 overhead).

### Added
- **Platform Runtime Profile (all SDKs, `F-PLAT-01`)** — added a
  metadata-only platform readiness profile contract, shared schema/vector,
  Python/JavaScript/Java helpers for deterministic profile hashing and
  readiness gap checks, plus docs and an offline example for production posture
  review.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, package/runtime versions, and
  security policy references to the `2.0.0` release line while keeping
  historical feature "since" labels intact.

---

## [1.9.0] — 2026-07-12

### Summary
**Ecosystem + Integrations.** v1.9.0 adds a dependency-light integration
catalog across Python, JavaScript, and Java, a shared integration recipe
schema/vector, compatibility docs for common AI stack tools, and offline
recipes including a full-stack runtime/export/eval/audit smoke flow. Feature
ID `F-INT-01`. Feature PR #81 (#80).

Tests: Python 296 · JavaScript 300 · Java modules green · docs build green ·
stable release gate green · JS smoke green · integration examples smoke green ·
Inspector benchmarks green (Python 1.39%/3.39%, JavaScript 0.31%/0.64%, Java
1.43%/1.06% metadata/full p50 overhead).

### Added
- **Integration catalog helpers (all SDKs, `F-INT-01`)** — Python exposes
  `list_integrations`, `get_integration`, `integration_metadata`, and
  `compatibility_matrix`; JavaScript exposes `listIntegrations`,
  `getIntegration`, `integrationMetadata`, `compatibilityMatrix`, and the
  `gavio/integrations` subpath; Java exposes `io.gavio.integrations`.
- **Integration recipe contract and vector** — added
  `spec/IntegrationRecipe.schema.json` and
  `test-vectors/integrations/catalog.json` for cross-SDK catalog parity.
- **Ecosystem docs and examples** — added the compatibility matrix, per-tool
  guides for LiteLLM, Portkey, Helicone, Langfuse, OpenLIT, promptfoo,
  LangChain, LangGraph, Vercel AI SDK, and OpenAI SDK, plus offline recipes
  under `examples/integrations/`.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, and package/runtime versions to
  the `1.9.0` release line while keeping historical feature "since" labels
  intact.

---

## [1.8.0] — 2026-07-12

### Summary
**Production Trust Package.** v1.8.0 adds metadata-only release evidence
bundles for production review. Python, JavaScript, and Java can now build and
verify deterministic trust bundles that summarize audit-chain integrity,
runtime-event privacy posture, control evidence, benchmark/document pointers,
release identity, and bundle tamper checks without storing raw prompt,
response, tool input, or tool output content. Feature ID `F-TRUST-01`.
Feature PR #79 (#78).

Tests: Python 291 · JavaScript 296 · Java modules green · docs build green ·
stable release gate green · Inspector benchmarks green (Python 4.18%/1.39%,
JavaScript 2.17%/1.26%, Java 1.00%/0.38% metadata/full p50 overhead).

### Added
- **Production Trust Bundle helpers (all SDKs, `F-TRUST-01`)** — Python
  exposes `build_production_trust_bundle`, `verify_production_trust_bundle`,
  `trust_bundle_hash`, and `TrustBundleVerification`; JavaScript exposes
  `buildProductionTrustBundle`, `verifyProductionTrustBundle`,
  `trustBundleHash`, `TRUST_SCHEMA_VERSION`, and the `gavio/trust` subpath;
  Java exposes `io.gavio.trust.ProductionTrust` and
  `ProductionTrustVerification`.
- **Trust bundle contract and vector** — added
  `spec/ProductionTrustBundle.schema.json` and
  `test-vectors/trust/production-trust-bundle.json` for deterministic bundle
  hashing, metadata-only privacy checks, audit-chain evidence, runtime-event
  evidence, and release-review controls.
- **Trust package docs and examples** — added `docs/trust-package.md`, the
  docs-site Trust Package guide, SDK guide sections, and
  `14-production-trust` runnable examples for Python, JavaScript, and Java.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, and package/runtime versions to
  the `1.8.0` release line while keeping historical feature "since" labels
  intact.

---

## [1.7.0] — 2026-07-12

### Summary
**Self-hosted Control Plane.** v1.7.0 adds an optional local/private control
plane for runtime projects, environments, hashed runtime keys, policy rollout,
budget config, event/audit search, and config snapshots. Python, JavaScript,
and Java can now fetch runtime config from that control plane, cache the last
successful config, and fail open or closed during outages. Feature PR #77
(#76).

Tests: Python 288 · JavaScript 293 · Java modules green · control-plane app
tests green · docs build green · stable release gate green · full GitHub CI
matrix green · Inspector benchmarks green (Python 4.62%/3.87%, JavaScript
2.41%/1.34%, Java 2.06%/1.61% metadata/full p50 overhead).

### Added
- **Self-hosted control-plane app** — added `apps/control-plane`, a
  dependency-light Node service with the `gavio-control-plane` command, a local
  admin UI, hashed runtime keys, role-gated admin mutations, metadata-first
  event/audit storage, and state persisted under `.gavio-control-plane/`.
- **Control-plane REST API** — added `/api/runtime/config`, `/api/projects`,
  `/api/environments`, `/api/keys`, `/api/teams`, `/api/policies`,
  `/api/policy-rollouts`, `/api/budgets`, `/api/events`,
  `/api/audit-records`, and `/api/config-snapshots`.
- **Runtime config clients (all SDKs)** — Python exposes
  `ControlPlaneClient`, `ControlPlaneError`, and `load_control_plane_config`;
  JavaScript exposes `ControlPlaneClient`, `ControlPlaneError`, and
  `loadControlPlaneConfig`; Java exposes `ControlPlaneClient`,
  `ControlPlaneOptions`, and `ControlPlaneException`.
- **Gateway config integration** — Python builders now accept
  `.control_plane(...)`, JavaScript `Gateway.fromConfig(...)` accepts
  `control_plane`, and Java builders accept `.controlPlane(...)` for loading
  runtime config before traffic starts.
- **Control-plane contracts and vectors** — added
  `spec/ControlPlaneRuntimeConfig.schema.json`,
  `spec/ControlPlaneEvent.schema.json`, and
  `test-vectors/control-plane/runtime-config.json`.
- **Control-plane docs and examples** — added `docs/control-plane.md`, the
  docs-site control-plane guide, and `13-control-plane` examples for Python,
  JavaScript, and Java.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, and package/runtime versions to
  the `1.7.0` release line while keeping historical feature "since" labels
  intact.

---

## [1.6.0] — 2026-07-12

### Summary
**Policy Pack Catalog.** v1.6.0 turns the Policy Pack architecture into a
signed, reusable domain catalog. Python, JavaScript, and Java can load domain
packs by name or path, verify deterministic SHA-256 manifest signatures, apply
local detector overrides, suppress auditable false positives, and construct
PII guards directly from catalog packs. Feature IDs `F-PACK-01/02/05`. Feature
PR #75 (#74).

Tests: Python 49 policy/vector/stable-gate tests · JavaScript 46 policy/vector
tests · Java modules green · docs build green · JS typecheck/build green ·
stable release gate green · catalog signature sanity green. Inspector
benchmarks were not rerun because this release changes PII policy-pack catalog
behavior, not Inspector runtime overhead.

### Added
- **Signed Policy Pack Catalog (all SDKs, `F-PACK-01/02/05`)** — added domain
  manifests for `core`, `finance`, `healthcare`, `legal`, `hr`, `support`,
  `code-security`, `regional/eu`, `regional/us`, and `regional/india` under
  `policy-packs/`.
- **Catalog load and guard APIs (all SDKs)** — Python exposes
  `PolicyPack.load`, `PolicyPack.load_path`, `list_policy_packs`, and
  `PiiGuard.from_policy_pack`; JavaScript exposes `loadPolicyPack`,
  `loadPolicyPackPath`, `listPolicyPacks`, and `piiGuardFromPolicyPack`; Java
  exposes `PolicyPacks.load`, `PolicyPacks.loadPath`,
  `PolicyPacks.listCatalog`, and `PiiGuard.fromPolicyPack`.
- **Signature, override, and suppression support (all SDKs)** — packs can verify
  SHA-256 manifest signatures, apply local detector overrides for action,
  severity, and redaction strategy, and skip detector-specific
  `suppressionPatterns` for auditable false-positive suppression.
- **Policy catalog contracts and vectors** — added
  `spec/PolicyPackManifest.schema.json`, `spec/PolicyRule.schema.json`,
  `spec/PolicyPackSignature.schema.json`, and
  `test-vectors/policy-packs/catalog.json`.
- **Policy catalog CLI and examples** — added `gavio policy list`,
  `gavio policy validate`, `gavio policy sign`, and
  `12-domain-policy-packs` runnable examples for Python, JavaScript, and Java.

### Changed
- **Current-version docs** — refreshed install snippets, package docs,
  docs-site version labels, examples index, stable gate fixtures, and
  package/runtime versions to the `1.6.0` release line while keeping historical
  feature "since" labels intact.

---

## [1.5.0] — 2026-07-12

### Summary
**Tool Runtime v2.** v1.5.0 turns Tool Runtime into a cross-SDK governance
layer for tool-call records. Python, JavaScript, and Java now understand
registry-backed tool definitions, required/granted permissions, risk metadata,
approval gates, deterministic replay records, provenance requirements, and
MCP-aware metadata without coupling Gavio to a specific MCP implementation.
Feature IDs `F-TOOL-05`, `F-TOOL-06`, `F-TOOL-07`, and `F-TOOL-08`. Feature
PR #73 (#72).

Tests: Python 278 · JavaScript 284 · Java modules green · docs build green ·
stable release gate green · Inspector benchmarks green (Python 0.58%/0.94%,
JavaScript -0.01%/-0.54%, Java 0.46%/1.10% metadata/full p50 overhead).

### Added
- **Tool Runtime v2 governance (all SDKs, `F-TOOL-05/06`)** — Python,
  JavaScript, and Java runtime decisions now include per-call action, risk,
  required/granted/missing permissions, approval-required status, approved
  status, block counts, and approval counts.
- **Approval and replay APIs (all SDKs, `F-TOOL-07`)** — added Python
  `replay_tool_runtime`, JavaScript `replayToolRuntime`, and Java
  `ToolRuntimeInterceptor.replay(...)` to reconstruct deterministic decisions
  from stored tool-call records.
- **Tool governance contracts and vectors** — added
  `spec/ToolDefinition.schema.json`, `spec/ToolPermission.schema.json`,
  `spec/ToolCallRecord.schema.json`, `spec/ToolApproval.schema.json`,
  `test-vectors/tool-runtime/permissions.json`, and
  `test-vectors/tool-runtime/replay.json`.
- **MCP-aware metadata capture (`F-TOOL-08`)** — Tool Runtime provenance now
  records MCP server/tool/session metadata when present while remaining a
  metadata-only validator around application-owned tool execution.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, and package/runtime versions to
  the `1.5.0` release line while keeping historical feature "since" labels
  intact.
- **Backward-compatible Tool Runtime metadata** — existing v0.14.0
  `metadata.tools.calls[]` schema, freshness, conflict, confidence, and
  provenance behavior remains supported alongside v2 `definitions`,
  `permissions`, `approvals`, and `records`.

---

## [1.4.0] — 2026-07-12

### Summary
**Prompt Registry + Evals.** v1.4.0 adds a cross-SDK prompt registry and
deterministic eval foundation. Python, JavaScript, and Java can now register
versioned chat templates, render them with metadata-only prompt lineage, and
run eval suites that report pass/fail scores, assertion details, and SHA-256
output hashes without storing raw model output. Feature IDs `F-EVAL-01` and
`F-EVAL-02`. Feature PR #71 (#70).

Tests: Python 271 · JavaScript 277 · Java modules green · docs build green ·
stable release gate green · JS package hygiene green · Inspector benchmarks
green (Python 4.76%/8.98%, JavaScript 1.41%/0.43%, Java 0.65%/0.37%
metadata/full p50 overhead).

### Added
- **Prompt Registry (all SDKs, `F-EVAL-01`)** — Python exposes
  `PromptTemplate`, `PromptRegistry`, and `RenderedPrompt`; JavaScript exposes
  the same surface from `gavio` and `gavio/prompts`; Java exposes
  `io.gavio.prompts.*`. Rendered prompts attach `PromptLineage` with template
  id, template version, variables, and RAG source references while keeping raw
  rendered prompt text out of lineage.
- **Eval suites (all SDKs, `F-EVAL-02`)** — added deterministic eval cases with
  built-in `contains`, `not_contains`, `equals`, and `regex` assertions. Reports
  include total/passed/failed counts, scores, assertion details, lineage, and
  `outputHash` instead of raw outputs.
- **Prompt/eval contracts and vectors** — added
  `spec/PromptTemplate.schema.json`, `spec/EvalReport.schema.json`, and
  `test-vectors/prompts/registry-evals.json` for cross-SDK rendering,
  missing-variable, lineage, scoring, and privacy conformance.
- **Prompt Registry + Evals docs and examples** — added dedicated docs pages
  and `09-prompt-registry-evals` examples for Python, JavaScript, and Java.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, and package/runtime versions to
  the `1.4.0` release line while keeping historical feature "since" labels
  intact.

---

## [1.3.0] — 2026-07-12

### Summary
**Observability + OTel.** v1.3.0 adds an OpenTelemetry-style bridge for the
runtime event contract introduced in v1.1.0. Runtime JSONL can now be converted
into deterministic span JSON across Python, JavaScript, and Java, with
metadata-only privacy defaults and shared schema/vector coverage. Feature ID
`F-OBS-07`. Feature PR #69 (#68).

Tests: Python 268 · JavaScript 274 · Java modules green · docs build green ·
stable release gate green · JS package hygiene green.

### Added
- **OpenTelemetry-style span exporters (all SDKs, `F-OBS-07`)** — Python
  exposes `OtelSpanExporter` and `otel_spans_from_events`; JavaScript exposes
  `otelSpanExporter` and `otelSpansFromEvents`; Java exposes
  `OtelSpanExporter`. The exporters map trace, provider, interceptor,
  governance, and error runtime events into deterministic span JSON with root,
  provider, and interceptor parentage.
- **OTel span contract and vectors** — added `spec/GavioOtelSpan.schema.json`
  and `test-vectors/otel/spans.json` to keep span shape, privacy stripping,
  error status, and exception-event behavior aligned across SDKs.
- **Runtime event conversion CLI (Python)** — added
  `gavio events convert --from <runtime.jsonl> --to otel-json` with optional
  `--service-name` for JSONL-friendly observability pipelines.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, docs-site nav, stable gate fixtures, and package/runtime versions to
  the `1.3.0` release line while keeping historical feature "since" labels
  intact.

---

## [1.2.0] — 2026-07-12

### Summary
**Cost Governance v2.** v1.2.0 turns Cost Intelligence into a production
governance surface: canonical budget policy and decision contracts, shared
budget vectors, budget-aware report rollups, in-memory budget stores, and
budget policy controls now ship across Python, JavaScript, and Java. Feature
PR #67 (#66).

Tests: Python 265 · JavaScript 272 · Java modules green · docs build green ·
stable release gate green · Inspector benchmarks green (Python 1.52%/3.33%,
JavaScript -0.07%/0.15%, Java 1.08%/1.30% metadata/full p50 overhead).

### Added
- **Budget policy and decision contracts** — added
  `spec/BudgetPolicy.schema.json`, `spec/BudgetDecision.schema.json`, and
  `spec/CostReport.schema.json` for Cost Governance v2 policy, decision, and
  report payloads.
- **Shared Cost Governance v2 vectors** — added
  `test-vectors/cost-governance/budget-decisions.json` and
  `test-vectors/cost-governance/cost-report.json` for allow, warn, block,
  fallback, downgrade, dry-run, budget remaining, and forecast rollups.
- **Budget policy controls (all SDKs)** — Python exposes `BudgetPolicy`,
  `BudgetDecision`, `InMemoryBudgetStore`, `BudgetPolicyControl`, and
  `evaluate_budget`; JavaScript exposes `BudgetPolicy`,
  `InMemoryBudgetStore`, `budgetPolicyControl`, and `evaluateBudget`; Java
  exposes `BudgetPolicyV2`, `BudgetDecision`, `InMemoryBudgetStore`,
  `BudgetPolicyControl`, and `BudgetPolicyEvaluator`.
- **Cost governance reports (all SDKs)** — added helpers that extend Cost
  Intelligence reports with `budgetLimitUsd`, `budgetRemainingUsd`,
  `forecastWindowSpendUsd`, and budget status rollups.
- **Cost report CLI (Python)** — added `gavio cost report --audit ...` with
  optional `--group-by`, `--since`, `--budget-policy`, and
  `--usage-elapsed-ratio` arguments for JSONL-friendly report generation.

### Changed
- **Current-version docs** — refreshed install snippets, examples, package
  docs, stable gate fixtures, and package/runtime versions to the `1.2.0`
  release line while keeping historical feature "since" labels intact.

---

## [1.1.0] — 2026-07-12

### Summary
**Positioning + Integration Foundation.** v1.1.0 establishes the runtime
event/export contract that later integrations can build on. It reuses the
Inspector event envelope as the public `GavioRuntimeEvent`, adds metadata-safe
JSONL runtime exporters across Python, JavaScript, and Java, documents how
Gavio fits beside gateway/observability/eval tools, and adds runnable runtime
export examples. Feature ID `F-EXP-01`. Feature PR #63; examples/docs PRs
#60, #61, and #62.

Tests: targeted runtime exporter tests across Python, JavaScript, and Java;
stable release gate green.

### Added
- **Runtime event/export contract (`F-EXP-01`)** — added
  `spec/GavioRuntimeEvent.schema.json`, shared runtime-event privacy vectors,
  and metadata-safe export helpers that strip `messages`, `content`, and
  `diff` before export by default.
- **JSONL runtime exporters (all SDKs)** — Python exposes
  `JsonlRuntimeExporter`, JavaScript exposes `jsonlRuntimeExporter`, and Java
  exposes `JsonlRuntimeExporter`. Adding an exporter enables metadata-mode
  runtime events without starting the Inspector HTTP server.
- **Integration documentation** — added guides for using Gavio beside LiteLLM,
  Portkey, Helicone, Langfuse, OpenLIT, and promptfoo while keeping Gavio's
  role focused on embedded runtime governance.
- **Runtime export examples** — added `08-runtime-export` examples for Python,
  JavaScript, and Java.
- **Expanded examples catalog** — refreshed the v1 examples and added runnable
  Policy Pack and Tool Runtime examples across the supported SDKs.

### Changed
- **Current-version docs** — refreshed install snippets, docs-site nav, package
  guides, examples, and release metadata to the `1.1.0` release line.
- **Java Inspector version** — synced the Inspector SDK version constant with
  the release version.

---

## [1.0.0] — 2026-07-12

### Summary
**Stable release gate.** v1.0.0 establishes Gavio's first stable API and
release-readiness gate across Python, JavaScript, and Java. It adds a
dependency-free stable-release validation command, wires it into CI and
publishing workflows, documents the API stability guarantee and 24-month 1.x
LTS policy, and syncs package/runtime versions for the stable release. Feature
PR #58 (#57).

Tests: Python 248 · JavaScript 260 · Java modules green · docs build green ·
stable release gate green · Inspector benchmarks green.

### Added
- **Stable release gate (`v1.0.0`)** — added `scripts/stable_release_gate.py`
  to validate lockstep SDK/runtime versions, changelog links, release docs,
  security/stability docs, benchmark evidence, workflow wiring, package
  hygiene, and zero mandatory Python core dependencies before a stable tag can
  publish. CI and tag release workflows now run the gate before package
  publishing.

### Changed
- **Stability/LTS docs** — added `STABILITY.md` and docs-site stability guidance
  for the v1.0.0 API stability guarantee and 24-month 1.x LTS policy.
- **Release metadata** — synced Python, JavaScript, and Java package/runtime
  versions to the stable release.
- **Current-version docs** — refreshed install snippets, docs-site nav, package
  docs, and Java artifact examples to the stable `1.0.0` release while keeping
  historical feature "since" labels intact.

### Security
- **Supported versions policy** — `SECURITY.md` now names the stable `1.x`
  support line, vulnerability reporting path, and 24-month LTS window. No
  external security audit result is claimed in this release.

---

## [0.14.0] — 2026-07-12

### Summary
**Tool Runtime.** v0.14.0 adds a cross-SDK Tool Runtime interceptor for
validating tool inputs/outputs, checking tool-result freshness, detecting
conflicting tool outputs, and recording tool provenance decisions before tool
results re-enter model context. Feature IDs `F-TOOL-01`, `F-TOOL-02`,
`F-TOOL-03`, and `F-TOOL-04`. Feature PR #56 (#55).

Tests: Python 246 · JavaScript 260 · Java modules green · docs build green.

### Added
- **Tool Runtime (all SDKs, `F-TOOL-01/02/03/04`)** — Python, JavaScript, and
  Java now expose a zero-dependency Tool Runtime interceptor that reads
  `metadata.tools.calls[]`, validates declared input/output schemas, applies
  freshness/TTL checks, flags conflicts across configured result keys, computes
  confidence, and records provenance under `ctx.tools["runtime"]` plus
  Inspector decision state. Shared contract:
  `test-vectors/tool-runtime/cases.json`.

---

## [0.13.0] — 2026-07-12

### Summary
**Adapter & positioning.** v0.13.0 adds an optional OpenRouter adapter, richer
runtime context fields, and updates the public positioning around Gavio as an
AI request runtime and inspector rather than only a gateway. Feature IDs
`F-ADP-02`, `F-RT-01`, and `F-DOC-V4`. Feature PR #54 (#53).

Tests: Python 239 · JavaScript 253 · Java modules green.

### Added
- **OpenRouter adapter (all SDKs, `F-ADP-02`)** — Python, JavaScript, and Java
  now expose an optional OpenRouter provider adapter using direct
  OpenAI-compatible chat-completions HTTP calls, `OPENROUTER_API_KEY`, optional
  base URL, timeout, and attribution headers.
- **Runtime context fields (all SDKs, `F-RT-01`)** — interceptor context now
  derives first-class `tenant`, `feature`, `cost`, `retry`, `tools`, and
  `policy` fields from request metadata while preserving the original metadata
  map for compatibility.

### Changed
- **Positioning docs (`F-DOC-V4`)** — README, docs homepage, and package guides
  now lead with AI Request Runtime / Inspector positioning and call out Cost
  Intelligence, Policy Packs, and OpenRouter in the provider surface.

---

## [0.12.0] — 2026-07-12

### Summary
**Policy Pack architecture.** Core PII and FinTech scanners now ship as
first-class policy packs across **Python, Java, and JavaScript**, with manifest
metadata and custom regex-rule packs for organization-specific detectors.
Feature IDs `F-PACK-01`, `F-PACK-02`, and `F-PACK-05`. Feature PR #52.

Tests: Python 236 · JavaScript 250 · Java modules green.

### Added
- **Policy Pack architecture (all SDKs, `F-PACK-01/02/05`)** — new
  `PolicyPack` manifest API exposes detector metadata, default action,
  redaction strategy, audit labels and scanner composition. The core PII and
  FinTech scanners are now backed by first-class packs while preserving
  existing scanner factory APIs, and custom organization regex-rule packs can
  define entity type, pattern, confidence, replacement prefix, action and
  redaction strategy. Shared contract:
  `test-vectors/policy-packs/manifest.json`. PR #52 (#51).

---

## [0.11.0] — 2026-07-12

### Summary
**Cost Intelligence.** Spend attribution, cost reporting, retry overhead,
cache savings, and scoped budget fallback now ship across **Python, Java, and
JavaScript**. Feature IDs `F-COST-01`, `F-COST-02`, and `F-COST-04`. Feature
PR #49.

Tests: Python 232 · JavaScript 246 · Java modules green.

### Added
- **Cost Intelligence (all SDKs, `F-COST-01/02/04`)** — request metadata can now
  carry scalar cost dimensions (`tenant`, `feature`, `user`, `endpoint`,
  `environment`, `workflow`, `tool`) that flow into Inspector trace summaries.
  `/api/stats` can group by those dimensions plus `session_id` and
  `middleware_chain`, and the new `/api/cost-report` endpoint returns total
  spend, average cost/request, retry count, retry overhead, cache savings and
  top-spend dimension lists. Shared contract:
  `test-vectors/inspector/cost-report.json`. PR #49.
- **Scoped budget fallback (all SDKs)** — `CostControl` now supports
  `tenant`, `feature`, `user`, and `model` scopes in addition to existing
  global/agent/session scopes, and can fall back to a cheaper model on hard-cap
  breach instead of only blocking. Soft warnings and fallback/block decisions
  surface as Inspector `governance.event` records with `kind="budget"`.

---

## [0.10.0] — 2026-07-04

### Summary
**Compliance & governance depth.** The four v0.5.0 carried-forward features land
across **Python, Java, and JavaScript** — license/copyright detection
(`F-QUA-10`), GDPR Art. 17 right-to-erasure (`F-QUA-09`), drift detection
(`F-GOV-07`), and image PII detection (`F-SEC-09`) — plus the first
**domain-aware policy pack** (FinTech identifiers). Feature PRs #40, #41, #43,
#45, #47.

Tests: Python 229 · JavaScript 243 · Java modules green.

### Added
- **License / copyright detection (all SDKs, `F-QUA-10`)** — new `licenseDetector`
  output validator flags known open-source license text (MIT, Apache-2.0,
  GPL-2.0/3.0, BSD-3-Clause, MPL-2.0) in model responses before it lands in user
  code. Matches a shipped corpus of hashed 8-word shingles — hashes only, no
  license text is bundled — and drops shingles shared by more than one license so
  a hit is discriminative (GPL-2.0 vs GPL-3.0 don't cross-fire). Detections
  surface in the guardrail outcome and audit record. Shared cases in
  `test-vectors/license/detection.json`. PR #40 (#33).
- **Right-to-erasure API — GDPR Art. 17 (all SDKs, `F-QUA-09`)** — a `subject_id`
  passed in request metadata is now persisted on every `AuditRecord` (new field,
  added to `spec/AuditRecord.schema.json`). Audit sinks gain
  `purge(subject_id) -> count`: a no-op for non-persistent sinks (stdout) and a
  real implementation on the built-in `JsonlSink`, which is now shipped in all
  three SDKs so erasure is testable everywhere. Scope and limits documented in
  `docs/interceptors.md`. PR #41 (#32).
- **Drift detection (all SDKs, `F-GOV-07`)** — new `DriftMonitor` interceptor
  with a pluggable `DriftDetector` interface and a default
  `StatisticalDriftDetector` (rolling-window baseline per metric, z-score
  threshold). Watches response-distribution signals (latency, tokens, cost,
  risk) and, when one drifts, emits a standalone `governance.event` inspector
  event — the reserved type is now wired in `spec/InspectorEvent.schema.json` —
  and counts it in `driftAlerts` on `/api/stats`. Observe-only. PR #43 (#31).
- **Image PII detection (all SDKs, `F-SEC-09`)** — new `ModalityScanner`
  interface + `ModalityGuard` interceptor extend the PII pipeline to image
  inputs passed on a side-channel `images` request field (added to
  `spec/GavioRequest.schema.json`). Each image's OCR text runs through the
  tier-1 text scanners and direct detections (e.g. `FACE`) are unioned in;
  detections land in the `AuditRecord`'s `pii_entity_types` like text PII, with
  `onDetect='block'` optional. Ships a reference `OcrModalityScanner` behind an
  optional dependency (Python `[ocr]` extra, JS `tesseract.js` peer dep, Java
  tess4j via reflection). Shared cases in `test-vectors/pii/image-detection.json`.
  PR #45 (#29).
- **FinTech domain policy pack (all SDKs)** — first domain-aware policy pack:
  a context-gated SWIFT/BIC scanner (`SWIFT_BIC`) and a US ABA routing-number
  scanner (`ROUTING_NUMBER`, mod-10 checksum), exposed as a
  `fintechScanners()` / `fintech_scanners()` / `DefaultScanners.fintech()`
  factory composed with the defaults. Shared cases in
  `test-vectors/pii/fintech-detection.json`. PR #47.

This completes the four v0.5.0 carried-forward features (F-QUA-10, F-QUA-09,
F-GOV-07, F-SEC-09) and adds the first domain policy pack; what remains for
v1.0.0 is the release gate (API-stability guarantee, docs site, published
benchmarks, security audit, LTS).

---

## [0.9.0] — 2026-07-04

### Summary
**Embedding call guard.** Embedding API calls no longer bypass the PII
pipeline: `Gateway.embed(texts)` runs every input through the same
interceptor chain as completions in **Python, Java, and JavaScript**
(`F-SEC-10`, carried forward from the v0.5.0 milestone). Plus a full refresh
of every user-facing doc surface. Feature PR #37, docs PR #38.

Tests: Python 175 · JavaScript 188 · Java 16 modules green.

### Added
- **Embedding call guard (all SDKs, `F-SEC-10`)** — new `Gateway.embed(texts)`
  entry point runs embedding inputs through the same interceptor pipeline as
  completions: PII guard scans/redacts every text before the provider's
  embedding API is called, governance and audit interceptors fire, and the
  Inspector traces the call. `GavioResponse` gains an `embeddings` field (one
  vector per input); provider adapters gain an optional `embed()` (MockProvider
  implements a deterministic reference); adapters without embedding support
  fail with a clear `ProviderError`. Shared redaction cases in
  `test-vectors/embedding/redaction.json` (#30).

### Changed
- Documentation refresh (#38): package READMEs rewritten to the current
  feature set (they described v0.1.0), Inspector + Embeddings sections added
  to all six per-SDK guides, main-README feature tables corrected, examples
  re-verified against the published packages, and new `05-inspector`
  multi-agent examples (Python + JS).

---

## [0.8.0] — 2026-07-04

### Summary
**Inspector performance budget, enforced.** The `benchmarks/inspector/`
overhead suite promised by the Inspector plan (deferred since v0.6.0) now
runs in CI for all three SDKs, and the registry project links (PyPI /
Maven Central) point at the real repository. Feature PR #35.

Measured overhead vs the plan's budgets (<1% metadata, <5% full):
metadata 0.13–0.58% p50, full 0–2.7% across the three SDKs.

Tests: Python 168 · JavaScript 181 · Java 16 modules green.

### Added
- **Inspector overhead benchmarks (`F-DX-09`)** — `benchmarks/inspector/`
  harnesses in all three SDKs measure per-request latency with the inspector
  disabled / metadata / full against a delay-padded mock provider, enforcing
  the INSPECTOR_PLAN §13 performance budget with CI thresholds (metadata p50
  overhead < 10% of the simulated call, full < 25%); new `benchmarks` CI job
  runs all three on every PR (#34).

### Fixed
- Registry project links: `pyproject.toml` Homepage/Repository/Changelog
  pointed at the unpurchased `gavio.io` domain and a nonexistent `gavio-ai`
  org (rendered broken on PyPI); now `manojmallick.github.io/gavio` and
  `github.com/manojmallick/gavio` (+ new Issues link). Maven parent pom
  `<url>` likewise (#35).

---

## [0.7.0] — 2026-07-04

### Summary
**Inspector: agentic & production mode.** The v0.7.0 milestone completes the
Inspector across **Python, Java, and JavaScript**: multi-agent call graphs and
session views, trace replay & edit-resend, RED stats, hash-chain verification,
PII-sanitized test-case export, a cost simulator, and the read-only
production dashboard over a persisted audit store (`gavio inspect --store`).
Feature IDs `F-OBS-10`, `F-DX-11`, `F-DX-08`, `F-DX-12`. Feature PR #27,
plus repo hygiene in #25.

Tests: Python 168 · JavaScript 181 · Java 16 modules green.

### Added
- **Agent call graph & session views (all SDKs, `F-OBS-10`)** — `GET /api/dag`
  (`?root=<trace_id>` or `?session_id=`) builds the multi-agent call graph from
  `parent_trace_id`/`agent_id` links with per-node and subtree
  cost/latency/status rollups; `GET /api/sessions` lists sessions with trace
  counts, errors, agents, total cost and duration. New DAG and Sessions tabs in
  the bundled Inspector UI. Shared DAG-assembly cases in
  `test-vectors/inspector/api-cases.json`.
- **Trace replay & edit-resend (all SDKs, `F-DX-11`)** — `POST /api/replay`
  re-fires a captured request through the live gateway (full interceptor chain,
  never bypassed), optionally with edited `messages`/`model`/`options`;
  returns the new `trace_id`. Available only in `full` capture mode — 403
  otherwise (gating cases shared in `test-vectors/inspector/api-cases.json`).
  Replay / Edit & resend actions in the UI trace detail.
- **Read-only dashboard over the audit store (`F-DX-08`)** — new `gavio`
  CLI (Python): `gavio inspect --store audit.jsonl` serves the Inspector in
  `metadata` mode from a persisted audit store with no running gateway. New
  `JsonlSink` audit sink (`jsonl://<path>`) writes the store. All SDKs gain
  `GET /api/stats` (RED aggregates — rate, error %, latency p50/p95/p99,
  tokens, cost, cache hit-rate, PII detections by entity type; `group_by`
  provider/model/agent_id, `since` filter) and a Stats tab in the UI;
  `GET /api/chain/verify` walks the `previous_hash` links (`F-OBS-02`
  surfaced) and reports the first broken record; `/api/traces?q=` looks up
  traces by trace-id or content-hash prefix.
- **Export trace as test case (all SDKs, `F-DX-12`)** —
  `GET /api/traces/{id}/export?format=test-vector|testkit-py|testkit-java|testkit-js`
  renders a captured trace as a shared `test-vectors/` JSON case or a runnable
  `GavioTestKit` unit test; detected PII values are replaced with synthetic
  fixtures before export. 403 in `metadata` mode.
- **Cost simulator (all SDKs)** — `GET /api/simulate-cost?trace_id=&model=`
  recosts a trace's token usage under a different model via `PricingProvider`.
  Trace summaries now carry token usage from `provider.call.end`.
- **Fleet observability extras** — prebuilt Grafana dashboard over the
  `F-OBS-08` Prometheus metrics (`docs/grafana/gavio-dashboard.json`) and the
  canonical InspectorEvent → OpenTelemetry span mapping (`docs/otel-mapping.md`,
  `F-OBS-07` groundwork).

### Changed
- Repo hygiene: harness-generated files (`.github/copilot-instructions.md`,
  `.claude/settings.json`) are untracked and gitignored (#25).

---

## [0.6.0] — 2026-07-03

### Summary
**Inspector: dev-time visualizer.** The v0.6.0 milestone — an embedded,
zero-dependency inspector across **Python, Java, and JavaScript**: live span
events for every request through the chain, a bounded ring buffer, a localhost
JSON API with SSE, and a self-contained web UI. Feature IDs `F-DX-09` and
`F-DX-10`. Streaming emits a reduced event set (the buffered path) and
`benchmarks/inspector/` is deferred to a follow-up.

Tests: Python 148 · JavaScript 162 · Java 157.

### Added
- **Inspector core (all SDKs, `F-DX-09`)** — embedded, zero-dependency dev-time
  visualizer plumbing: an in-process `InspectorBus` emits span events
  (`trace.start`, per-interceptor `before`/`after` start+end with duration,
  mutation flag and decision records, `provider.call.*`, `trace.error`,
  `trace.end`) while a request moves through the chain; a bounded ring buffer
  assembles them into traces; an embedded localhost HTTP server (stdlib only)
  serves a JSON API — `/api/health`, `/api/pipeline` (chain composition +
  ordering lints), `/api/traces`, `/api/traces/{id}`, `/api/stream` (SSE).
  Canonical event contract in `spec/InspectorEvent.schema.json`; shared
  `test-vectors/inspector/event-sequences.json` cases run in all three suites.
- **Inspector UI (all SDKs, `F-DX-10`)** — single self-contained HTML file
  (source: `inspector-ui/index.html`, vendored into each package) served at
  `/`: live trace list over SSE, per-trace waterfall with span timings,
  mutation diff / PII redaction pane, decision records, pipeline view with
  ordering lints. Works fully offline; no external resources.
- **Capture modes** — `full` / `redacted` / `metadata` with *structural*
  content gating: `metadata` events carry no message/response content by
  construction; `full` refuses to start outside dev mode without an explicit
  acknowledgement; secret values are masked in every mode. Inspector is off
  by default; enable via the gateway builder (`inspect(...)`) or
  `GAVIO_INSPECT=1` (`GAVIO_INSPECT_PORT`, `GAVIO_INSPECT_MODE`). Server binds
  `127.0.0.1` by default; non-loopback binds require an auth token.
- **`InterceptorContext.inspect(key, value)`** — custom interceptors can attach
  decision records to their span events; interceptor state entries keyed by
  interceptor name (e.g. `cost_router`) surface automatically.

---

## [0.5.1] — 2026-07-03

### Summary
**Patch release.** Documentation corrections and expanded `F-GOV-06` test
coverage — no runtime code changes; the published artifacts are functionally
equivalent to v0.5.0.

### Added
- **Cost-optimiser routing test coverage (`F-GOV-06`)** — each SDK's governance
  suite now asserts the decision record `CostRouter` writes to
  `ctx.state["cost_router"]` (`rerouted`, `original_model`, `complexity_score`)
  and covers the threshold boundary (a score exactly at `complexity_threshold`
  does not reroute).

### Fixed
- **Docs** — corrected stale version pins missed by the v0.5.0 cut: the Java
  package README (`0.1.0` → `0.5.0`), the `examples/java` Maven poms
  (`0.4.0` → `0.5.0`), and `SECURITY.md` supported versions (`0.1.x` → `0.5.x`).
  Refreshed the root README (architecture section, feature list through v0.5.0)
  and completed the changelog version-comparison footer links.

---

## [0.5.0] — 2026-07-03

### Summary
**Cost-optimiser routing.** The first slice of the roadmap's "Advanced
features" milestone — `CostRouter` across **Python, Java, and JavaScript**.
Note this is a partial release against that theme: `F-SEC-09/10`, `F-GOV-07`,
`F-OBS-10`, `F-QUA-09/10`, and `F-DX-08` remain unstarted. Feature ID
`F-GOV-06`.

Tests: Python 133 · JavaScript 146 · Java 140.

### Added
- **Cost-optimiser routing (all SDKs, `F-GOV-06`)** — `CostRouter` reroutes a
  request to a cheaper `simple_model` when a pluggable `ComplexityScorer`
  scores its prompt below `complexity_threshold`. Ships a zero-dependency
  default `HeuristicComplexityScorer` (prompt length via the same token
  estimator `PricingProvider` uses, plus reasoning-keyword density). Java's
  `GavioRequest` gained `withModel(String)` alongside its existing
  `withProvider`, so the rewrite mirrors the established
  `withMessages`/`withProvider`/`copy_with_messages` idiom used by
  `LoadBalancer`/`FallbackChain`. Records its decision in
  `ctx.state["cost_router"]` — no `AuditRecord` schema changes needed, since
  the audit trail's `model` field already reflects the rerouted model.

---

## [0.4.0] — 2026-07-02

### Summary
**Distributed caching.** A production-grade Redis backend for `SemanticCache`
— shared exact and semantic cache hits across processes — across **Python,
Java, and JavaScript**. Deferred from v0.2.0, closes out the last of the three
issues that carried over from that release. Feature ID `F-CACHE-04`.

### Added
- **Redis cache backend (all SDKs, `F-CACHE-04`)** — `RedisBackend`/`RedisVectorBackend`
  (Python), `redisCacheBackend`/`redisVectorBackend` (JavaScript), and
  `RedisCacheBackend`/`RedisVectorBackend` (Java) give `SemanticCache` a
  production-grade, distributed cache backend so exact and semantic hits are
  shared across processes. Python uses the optional `redis` package
  (`pip install gavio[redis]`); JavaScript and Java hand-roll a minimal RESP2
  client over `node:net`/`java.net.Socket` — zero runtime dependencies, matching
  the project's stdlib-only provider adapters. Entries are namespaced under a
  Redis Set index so `clear()` only removes keys the backend itself wrote,
  never the whole database; TTLs use Redis's native expiry. The in-memory
  backends remain the zero-infra default (design principle P4). Wired into the
  Python and JavaScript config loaders (`semantic_cache.backend: redis`).
  Deferred from v0.2.0.

---

## [0.3.0] — 2026-07-01

### Summary
**Observability depth.** Prompt lineage, Prometheus metrics, and composite risk
scoring — plus buffered streaming reliability — across **Python, Java, and
JavaScript**. Two new interceptor families ship: `metrics` (Prometheus) and
`quality` (risk scoring). Semver stability continues.

Tests: Python 122 · JavaScript 135 · Java (16 Maven modules). Feature IDs
`F-OBS-04`, `F-OBS-08`, `F-QUA-06`, `F-REL-06`.

### Added
- **Prompt lineage (all SDKs, `F-OBS-04`)** — new `PromptLineage` value type
  (`template_id`, `template_version`, `variables`, `rag_chunks`) plus a `RagChunk`
  source reference (`source`, `chunk_id`, `score`). Attach it to a
  `GavioRequest` (`lineage=`) and the `AuditInterceptor` copies it into the
  `AuditRecord` so any prompt can be reconstructed from its template, variable
  bindings, and RAG sources. RAG chunk **text is never stored** — only source
  references — keeping the audit record metadata-only. Lineage participates in
  the hash-chain `contentHash()`. Exported from each SDK's public API; documented
  in `spec/GavioRequest.schema.json` and `spec/AuditRecord.schema.json`. First
  feature of **v0.3.0 (Observability depth)**.
- **Streaming reliability (all SDKs, `F-REL-06`)** — `Gateway.stream(...)` runs a
  completion through the provider's streaming API but buffers the response in
  full via a new `StreamBuffer` before the post-interceptor pipeline (guardrails,
  PII restore, audit) runs — so every post-interceptor sees, and can rewrite or
  block, the complete response before any chunk reaches the caller. Adds
  `ProviderAdapter.build_stream_response()` (estimates usage from the buffered
  text) and a mock streaming implementation for dev mode. Executor policies
  (retry, circuit breaker, cache) are not applied to the streaming path in this
  release. First v0.2.0-planned reliability gap now closed. `StreamBuffer` lives
  in `io.gavio.providers` in Java (core cannot depend on the reliability module).
- **Prometheus metrics (all SDKs, `F-OBS-08`)** — `MetricsInterceptor` records
  per-request metrics into a `PrometheusMetrics` registry, exposed via
  `render()` as the Prometheus text exposition format: `gavio_requests_total`,
  `gavio_tokens_total{kind}`, `gavio_cost_usd_total`, `gavio_request_latency_ms`
  (histogram), and `gavio_cache_hits_total` — all labelled by `provider` and
  `model`. Hand-rolled exposition, **zero runtime dependencies**. Java ships a
  new `gavio-interceptor-metrics` module; JS adds the `gavio/interceptors/metrics`
  subpath. Another v0.3.0 (Observability depth) feature.
- **Risk scoring (all SDKs, `F-QUA-06`)** — `RiskScorer` folds the per-request
  signals other interceptors leave on the context — PII entities found, guardrail
  outcome (`FAIL`/`HITL`), and the prompt-injection risk — into a single
  composite score in `[0, 1]`, written to `ctx.risk_score` and recorded on the
  `AuditRecord`. Configurable weights (default PII 0.3 · guardrail 0.4 · injection
  0.3), clamped; exposes a pure `score(...)` method. Introduces a new `quality`
  interceptor family (`gavio.interceptors.quality`, `gavio/interceptors/quality`,
  Java module `gavio-interceptor-quality`) for the F-QUA features. Another v0.3.0
  feature.

### Fixed
- **JS audit hash chain** — `AuditRecord.toCanonicalJson()` now sorts keys
  recursively so nested fields (`tokenUsage`, `lineage`) actually contribute to
  the chain hash; the previous array-replacer dropped all nested content.

---

## [0.2.0] — 2026-07-01

### Summary
**Production core.** Semantic + exact caching, hash-chain audit, multi-agent DAG
trace, circuit breaker, load balancing, budget/rate-limit/RBAC governance,
guardrails (JSON-schema + regex), prompt-injection defense, and Gemini / Azure
OpenAI / Ollama providers — shipped across **Python, Java, and JavaScript**.

Tests: Python 102 · JavaScript 115 · Java 108. Feature IDs `F-CACHE-01/02/03`,
`F-OBS-02/03`, `F-REL-03/04`, `F-GOV-02/03/04`, `F-QUA-01/02`, `F-SEC-05`,
`F-DX-04/05`. The entries below were implemented first in the Python reference
and ported to JS and Java in lockstep (Java's standalone JSON config loader is
deferred — the Spring starter / explicit builders are the idiomatic path).

### Added
- **Caching** — `SemanticCache` interceptor:
  - `F-CACHE-01` exact SHA-256 cache (keyed on provider + model + messages +
    options); a hit returns the cached response and skips the provider.
  - `F-CACHE-02` semantic cache — cosine similarity over embeddings, with a
    configurable `similarity_threshold`. Ships a zero-dependency
    `HashingEmbedder` (pluggable `Embedder` protocol for real embedders).
  - `F-CACHE-03` in-memory backends — `MemoryBackend` (exact) and
    `InMemoryVectorBackend` (semantic).
  - Implemented as an `ExecutorPolicy` (register outermost); sets `cache_hit` /
    `cache_type` on the response. Composes with `PiiGuard` — a cache hit still
    restores PII in the response.
- **Hash-chain audit (all SDKs, `F-OBS-02`)** — `AuditInterceptor(hash_chain=True)`
  links each record via `previous_hash` (SHA-256 of the prior record);
  `verify_chain(records)` detects any edit, reorder, or deletion.
- **Multi-agent DAG trace (all SDKs, `F-OBS-03`)** — `build_call_graph(records)`
  reconstructs the call graph from `parent_trace_id` + `agent_id`.
- **Circuit breaker (all SDKs, `F-REL-03`)** — `CircuitBreaker` `ExecutorPolicy`
  with closed/open/half-open states; fast-fails with `CircuitOpenError` while
  open, probes on recovery.
- **Governance (all SDKs)** — `CostControl` budget caps (`F-GOV-02`, soft/hard per
  scope+window), `RateLimiter` (`F-GOV-03`, requests + tokens per minute),
  `ModelPolicy` RBAC allowlists (`F-GOV-04`). New errors: `RateLimitExceededError`,
  `ModelNotAllowedError`.
- **Guardrails (all SDKs)** — `GuardrailsInterceptor` (`ExecutorPolicy`, on_failure
  error/retry/warn) with `JsonSchemaValidator` (`F-QUA-01`, zero-dep JSON Schema
  subset) and `RegexDenylistValidator` / `RegexAllowlistValidator` (`F-QUA-02`);
  records `guardrail_outcome` for the audit trail.
- **New provider adapters (all SDKs)** — `GeminiAdapter` (role/system mapping),
  `AzureOpenAIAdapter` (deployment routing), `OllamaAdapter` (local, free);
  registered in the provider registry with pricing entries. All stdlib HTTP.
- **Prompt injection defense (all SDKs, `F-SEC-05`)** — `PromptInjectionGuard`
  (curated pattern corpus + optional semantic similarity); block or flag,
  records a `risk_score`. New `PromptInjectionError`.
- **Load balancing (all SDKs, `F-REL-04`)** — `LoadBalancer` `ExecutorPolicy`,
  weighted round-robin across a pool of provider adapters.
- **OpenAI drop-in shim (all SDKs, `F-DX-04`)** — `gavio.shim.openai.GavioOpenAI`
  with an OpenAI-client-shaped `chat.completions.create` / `acreate`.
- **Config loader (Python + JavaScript; Java deferred, `F-DX-05`)** — `Gateway.from_config(path | dict)`
  builds a gateway from JSON (stdlib) or YAML (optional PyYAML), with `${ENV}`
  expansion.

### Changed
- `ExecutorPolicy` moved to `gavio.interceptors.executor` (re-exported from
  `interceptors.reliability.policy` for compatibility) so caching and
  reliability share it.

---

## [0.1.0] — 2026-07-01

### Summary
Foundation release. Working interceptor pipeline with provider adapters,
PII Guard (regex tier), audit logging, retry/fallback, and dev mode.
Ships across Python, Java, and JavaScript simultaneously — plus a canonical
spec (`spec/`) and shared cross-SDK test vectors (`test-vectors/`).

**224 tests total** — Python 63, JavaScript 85, Java 76, each including the
shared vectors. CI (`.github/workflows/ci.yml`) runs all three suites on every
push and PR. Provider adapters use no vendor SDKs (stdlib HTTP everywhere).
The JavaScript package ships a dual ESM + CJS build with per-subpath type
definitions. Monotonic UUID v7 trace-id format verified identical across SDKs.

### Added

#### Core
- `Gateway` class with fluent builder API (Python, Java, JavaScript)
- `InterceptorChain` — pre/post interceptor pipeline
- `GavioRequest` / `GavioResponse` — canonical request/response model
- `ScanContext` — per-request context for interceptors
- `trace_id` (UUID v7) assigned to every request
- `agent_id` and `parent_trace_id` fields on every request
- `dev_mode` flag — runs entirely in-process, no network, no API key needed
- `dry_run` flag — interceptors log but never block or modify

#### PII Guard (`F-SEC-01`) — all three languages
- `PiiScanner` interface — extensible scanner API with `scan()`, `tier`, `entity_type`
- `ScannerRegistry` — register/discover scanners at runtime
- `PiiMatch` — typed match result with start, end, value, replacement, confidence
- Built-in scanners: `EmailScanner`, `IbanScanner`, `BsnScanner`, `CreditCardScanner`, `PhoneScanner`, `IpAddressScanner`, `SsnScanner`
- Sensitivity levels: `strict`, `balanced`, `permissive`
- Modes: `redact`, `mask`, `tag`, `block`
- Restore-on-response: replaces tokens back with originals in the response
- Entity type + count logging (never logs raw PII values)

#### Secret Scanner (`F-SEC-04`) — all three languages
- `SecretScanner` — detects API keys (OpenAI `sk-...`, Anthropic `sk-ant-...`), AWS `AKIA...`, GitHub tokens, JWTs, PEM private keys, database connection strings

#### Retry & Fallback (`F-REL-01`, `F-REL-02`) — all three languages
- `RetryInterceptor` — exponential backoff with jitter, configurable max attempts
- `FallbackChain` — sequential provider fallback on failure
- `TimeoutPolicy` — per-request timeout enforcement (`F-REL-07`)

#### Cost Tracking (`F-GOV-01`) — all three languages
- Real-time cost estimation per request
- Provider pricing tables for OpenAI, Anthropic (updated via config)
- `cost_usd` field on every `GavioResponse`

#### Audit Interceptor (`F-OBS-01`) — all three languages
- `AuditInterceptor` — captures full request/response metadata
- `AuditRecord` — immutable record: trace_id, provider, model, token usage, cost, latency, PII entity types/counts, interceptors fired, cache hit, risk score, lineage
- `AuditSink` — extensible sink interface with `write(AuditRecord)` method
- `StdoutSink` — human-readable output for development (`F-OBS-05`)
- Schema version `"1.0"` on every `AuditRecord`

#### Provider Adapters
- `OpenAIAdapter` — GPT-4o, o1, embeddings. Streaming supported.
- `AnthropicAdapter` — Claude Sonnet, Haiku, Opus. Streaming supported.

#### Developer Experience
- Local dev mode (`F-DX-01`) — `MockProvider`, `MemoryCacheBackend`, `StdoutSink` wired automatically
- Dry-run mode (`F-DX-02`) — all interceptors log without modifying requests

#### Python-specific
- `gavio` package on PyPI with zero mandatory dependencies
- `gavio[presidio]`, `gavio[redis]`, `gavio[otel]`, `gavio[elasticsearch]`, `gavio[all]` extras
- Async-first with `complete()` / `complete_sync()` sync wrapper
- Full PEP 484 type hints, `py.typed` marker, mypy strict-mode compatible
- Supports Python 3.10, 3.11, 3.12

#### Java-specific
- Multi-artifact Maven structure: `gavio-core`, `gavio-interceptor-pii`, `gavio-interceptor-audit`, `gavio-interceptor-reliability`
- `CompletableFuture<GavioResponse>` async API
- Java 17+ required (uses records, sealed interfaces)
- Java 21 virtual thread support for non-blocking I/O
- `gavio-spring-boot-starter` — auto-configuration, health indicator, Micrometer metrics
- All artifacts published to Maven Central under `io.github.manojmallick` groupId

#### JavaScript-specific
- `gavio` package on npm — ESM + CJS dual build
- Full TypeScript 5.0+ type definitions included
- Zero mandatory dependencies
- Sub-path imports for tree-shaking (`gavio/interceptors/pii`, etc.)
- Node.js 18+, Deno 1.40+, Bun 1.0+ support
- `edgeMode: true` for Cloudflare Workers / Vercel Edge

#### Cross-SDK infrastructure
- Canonical spec in `spec/` — JSON Schema (Draft 2020-12) for `GavioRequest`,
  `GavioResponse`, `AuditRecord`, `PiiMatch`, `InterceptorResult`
- Shared test vectors in `test-vectors/pii/` (`checksums.json`, `detection.json`)
  loaded and run by all three SDKs, enforcing behavioural parity
- CI workflow (`.github/workflows/ci.yml`) running Python (3.10–3.12),
  JavaScript (Node 18/20/22), and Java (17/21) suites incl. the shared vectors
- Repository governance: `LICENSE` (MIT), `CONTRIBUTING.md`, `SECURITY.md`,
  `CODE_OF_CONDUCT.md`

### Notes
- APIs may change before v0.2.0. Semver stability guarantee begins at v0.2.0.

---

## [0.2.0] — TBD _(planned)_

### Summary
Production-ready release. Semantic caching, multi-agent DAG tracing, hash-chain
audit records, NER-based PII scanning, circuit breaker, and guardrails.

### Planned additions

#### PII Guard — NER tier (`F-SEC-02`)
- `PresidioAdapterScanner` (Python) — wraps Microsoft Presidio + spaCy
- `RemoteNerScanner` (Java, JavaScript) — calls Presidio REST sidecar
- `HuggingFaceNerScanner` (Python) — configurable transformer model

#### Prompt Injection Defense (`F-SEC-05`)
- `PromptInjectionGuard` interceptor — pattern + semantic similarity detection
- Ships with a curated injection attack corpus

#### Reliability (`F-REL-03`, `F-REL-04`, `F-REL-06`)
- `CircuitBreaker` — open/half-open/closed state machine
- `LoadBalancer` — weighted round-robin + latency-aware routing
- `StreamBuffer` — buffer streaming response before post-interceptors run

#### Caching (`F-CACHE-01`, `F-CACHE-02`, `F-CACHE-03`, `F-CACHE-04`)
- `SemanticCache` interceptor with two-level cache
- `MemoryCacheBackend` — default zero-dependency dev backend
- `RedisCacheBackend` — production distributed cache
- SHA-256 exact cache: microsecond-latency exact match
- Semantic cache: cosine similarity on embeddings, configurable threshold

#### Governance (`F-GOV-02`, `F-GOV-03`, `F-GOV-04`)
- `CostControl` — hard and soft budget caps per user/agent/project/window
- `RateLimiter` — token-bucket, configurable scope and window
- `ModelPolicy` — per-role model allowlists (RBAC)

#### Audit (`F-OBS-02`, `F-OBS-03`)
- Hash-chain tamper detection — SHA-256 of previous record embedded in each entry
- Multi-agent DAG trace — `parent_trace_id` + `agent_id` form a directed acyclic graph
- `ElasticsearchSink` — production structured log storage

#### Guardrails (`F-QUA-01`, `F-QUA-02`)
- `GuardrailsInterceptor` with `OutputValidator` extension interface
- `JsonSchemaValidator` — validate structured LLM output against JSON Schema
- `RegexDenylistValidator` — pattern-based content filtering

#### Developer Experience (`F-DX-03`, `F-DX-04`, `F-DX-05`)
- `GavioTestKit` — unit-test interceptor chains in isolation (all 3 languages)
- OpenAI drop-in shim — point existing OpenAI SDK code at Gavio without changes
- JSON Schema config — `gateway.yaml` validated by published schema, IDE autocomplete

#### New providers
- `GeminiAdapter` — Gemini Pro, Flash, Ultra
- `AzureOpenAIAdapter` — Azure OpenAI deployment-based routing
- `OllamaAdapter` — local model support

### ⚠️ Breaking changes in v0.2.0
- `Gateway.call()` is deprecated in favour of `Gateway.complete()`. Removed in v0.4.0.
- `AuditRecord.version` field renamed to `AuditRecord.schema_version` for clarity.

---

## [0.3.0] — TBD _(planned)_

### Summary
Observability depth. Prompt lineage, LLM judge, hallucination detection,
OpenTelemetry, Prometheus metrics, and model version pinning.

### Planned additions
- `F-SEC-03` — PII Guard LLM tier (`LlmContextScanner`)
- `F-SEC-06` — Tool result sanitizer
- `F-REL-05` — Latency hedging
- `F-REL-08` — Model version pinning + change alerts
- `F-CACHE-05` — pgvector semantic backend
- `F-CACHE-06` — Cache invalidation API
- `F-OBS-04` — Prompt lineage (template + variables + RAG chunk sources)
- `F-OBS-07` — OpenTelemetry OTLP export
- `F-OBS-08` — Prometheus metrics (Micrometer for Java, prom-client for JS)
- `F-OBS-09` — PostgreSQL audit sink
- `F-QUA-03` — LLM judge validator
- `F-QUA-04` — Hallucination / faithfulness detection
- `F-QUA-06` — Risk scoring (composite: PII + guardrail + quality signals)
- `F-DX-06` — Config validation CLI (`gavio validate gateway.yaml`)
- `F-DX-07` — Scaffold CLI (`gavio init`)
- New providers: AWS Bedrock, Cohere

---

## [0.4.0] — TBD _(planned)_

### Summary
Compliance and oversight. EU AI Act + DORA export, human-in-the-loop gate,
data residency routing, multi-jurisdiction PII.

### Planned additions
- `F-SEC-07` — Data residency routing (GDPR Art. 44)
- `F-SEC-08` — Multi-jurisdiction PII rulesets (GDPR, HIPAA, PDPA, DPDP, LGPD, PIPL)
- `F-GOV-05` — A/B model routing with quality scoring
- `F-QUA-05` — Human-in-the-loop gate (pause + review queue)
- `F-QUA-07` — EU AI Act audit export (Article 12/13 signed bundle)
- `F-QUA-08` — DORA RTS audit export (Article 9 evidence package)
- New providers: HuggingFace Inference Endpoints, Vertex AI (direct)

### ⚠️ Breaking changes in v0.4.0
- `Gateway.call()` removed (deprecated since v0.2.0)

---

## [0.5.0] — TBD _(planned)_

### Summary
Advanced features. Multimodal, cost optimisation, right to erasure, dashboard.

### Planned additions
- `F-SEC-09` — Image PII detection (OCR + face detection on image inputs)
- `F-SEC-10` — Embedding call guard (PII pipeline on embedding API calls)
- `F-GOV-06` — Cost optimiser routing (auto-route to cheaper models)
- `F-GOV-07` — Drift detection (alert on response distribution changes)
- `F-OBS-10` — Agent call graph replay
- `F-QUA-09` — Right-to-erasure API (GDPR Art. 17 `purge(subject_id)`)
- `F-QUA-10` — License / copyright detection in responses
- `F-DX-08` — Read-only web dashboard (traces, costs, cache hit rate)

---

[Unreleased]: https://github.com/manojmallick/gavio/compare/v2.5.0...HEAD
[2.5.0]: https://github.com/manojmallick/gavio/compare/v2.4.0...v2.5.0
[2.4.0]: https://github.com/manojmallick/gavio/compare/v2.3.0...v2.4.0
[2.3.0]: https://github.com/manojmallick/gavio/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/manojmallick/gavio/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/manojmallick/gavio/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/manojmallick/gavio/compare/v1.9.0...v2.0.0
[1.9.0]: https://github.com/manojmallick/gavio/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/manojmallick/gavio/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/manojmallick/gavio/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/manojmallick/gavio/compare/v1.5.0...v1.6.0
[1.5.0]: https://github.com/manojmallick/gavio/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/manojmallick/gavio/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/manojmallick/gavio/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/manojmallick/gavio/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/manojmallick/gavio/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/manojmallick/gavio/compare/v0.14.0...v1.0.0
[0.14.0]: https://github.com/manojmallick/gavio/compare/v0.13.0...v0.14.0
[0.13.0]: https://github.com/manojmallick/gavio/compare/v0.12.0...v0.13.0
[0.12.0]: https://github.com/manojmallick/gavio/compare/v0.11.0...v0.12.0
[0.11.0]: https://github.com/manojmallick/gavio/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/manojmallick/gavio/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/manojmallick/gavio/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/manojmallick/gavio/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/manojmallick/gavio/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/manojmallick/gavio/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/manojmallick/gavio/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/manojmallick/gavio/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/manojmallick/gavio/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/manojmallick/gavio/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/manojmallick/gavio/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/manojmallick/gavio/releases/tag/v0.1.0
