# Gavio Test Vectors

Shared, language-agnostic test cases that **every SDK must pass**. They turn
cross-SDK parity from a manual promise into a runnable contract: each SDK loads
these JSON files in its own test suite and asserts the same results.

If a change to detection logic, checksums, or redaction breaks parity, the
offending SDK's test-vector run goes red.

## Files

| File | What it checks |
|---|---|
| [`pii/checksums.json`](./pii/checksums.json) | Single-scanner cases — regex + checksum logic (IBAN mod-97, BSN 11-proef, Luhn, IP validation). Each case: run the named scanner over `text`, assert `matchCount > 0 === shouldMatch`. |
| [`pii/detection.json`](./pii/detection.json) | Full-pipeline cases — run the default `PiiGuard` over `text`, collect unique entity types, sort, compare to `expectedTypes`. Exercises the whole scanner set plus overlap resolution. |
| [`pii/image-detection.json`](./pii/image-detection.json) | Image PII cases (F-SEC-09) — a stubbed `ModalityScanner` yields `ocrText` + `entityTypes`; the modality guard runs the text scanners over the OCR text, unions the direct detections, and compares the sorted entity types to `expectedTypes`. Image bytes are stubbed so the contract is deterministic across SDKs. |
| [`pii/fintech-detection.json`](./pii/fintech-detection.json) | FinTech policy pack cases — run a `PiiGuard` configured with only `fintechScanners()` over `text`, collect the sorted entity types, compare to `expectedTypes`. Exercises context-gated SWIFT/BIC and ABA routing-number checksum. |
| [`policy-packs/manifest.json`](./policy-packs/manifest.json) | Policy Pack architecture cases (F-PACK-01/02/05) — verify core and FinTech pack manifests, scanner lists, and custom regex-rule pack detection across SDKs. |
| [`policy-packs/catalog.json`](./policy-packs/catalog.json) | Policy Pack Catalog cases — verify catalog listing, signed manifest loading, overrides, suppression rules, and domain pack detection across SDKs. |
| [`control-plane/runtime-config.json`](./control-plane/runtime-config.json) | Self-hosted control-plane cases — verify runtime config shape, policy source loading, event search filters, RBAC denial, metadata stripping, and offline cache fallback. |
| [`trust/production-trust-bundle.json`](./trust/production-trust-bundle.json) | Production Trust Package case — verify deterministic bundle hashing, metadata-only privacy posture, audit-chain evidence, runtime event evidence, and release-review controls. |
| [`integrations/catalog.json`](./integrations/catalog.json) | Ecosystem integration recipes — verify compatibility metadata, role boundaries, docs paths, and offline example paths across SDKs. |
| [`integrations/adapters.json`](./integrations/adapters.json) | Ecosystem adapter payload cases — verify LiteLLM, promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and Vercel AI SDK payload shapes, trace propagation, metadata hashes, and raw-content omission across SDKs. |
| [`platform-runtime/profile.json`](./platform-runtime/profile.json) | Platform Runtime Profile cases — verify deterministic profile hashing, metadata-only posture, required runtime surfaces, readiness score, and gap codes. |
| [`runtime-events/export-redaction.json`](./runtime-events/export-redaction.json) | Runtime exporter privacy contract (F-EXP-01) — verify metadata-only export strips content-bearing event fields while preserving trace and decision metadata. |
| [`otel/spans.json`](./otel/spans.json) | OTel bridge cases (F-OBS-07) — map runtime events into OpenTelemetry-style spans with parent links, status, timestamps, attributes, and metadata-only privacy. |
| [`prompts/registry-evals.json`](./prompts/registry-evals.json) | Prompt Registry + Evals cases (`F-EVAL-01/02`) — render versioned prompt templates, validate missing variables, attach metadata-only lineage, score eval cases, and keep raw outputs out of reports. |
| [`tool-runtime/permissions.json`](./tool-runtime/permissions.json) | Tool Runtime v2 permissions and approval cases — registry-backed permission checks, destructive approval gates, provenance requirements, and MCP metadata capture. |
| [`tool-runtime/replay.json`](./tool-runtime/replay.json) | Tool Runtime v2 replay cases — reconstruct deterministic runtime decisions from stored tool-call records. |
| [`license/detection.json`](./license/detection.json) | License detection cases (F-QUA-10) — run the default license detector over `text`, collect the sorted SPDX ids, compare to `expectedLicenses`. Snippets are synthetic license text; the shipped corpus contains only shingle hashes. |
| [`inspector/cost-report.json`](./inspector/cost-report.json) | Cost Intelligence cases (F-COST-02) — run the Inspector cost-report builder over trace summaries with attribution dimensions, compare total spend, grouped spend, retry overhead, cache savings, and top-spend dimensions. |
| [`cost-governance/budget-decisions.json`](./cost-governance/budget-decisions.json) | Cost Governance v2 cases — evaluate shared `BudgetPolicy` decisions for allow, warn, block, fallback, downgrade, and dry-run actions. |
| [`cost-governance/cost-report.json`](./cost-governance/cost-report.json) | Cost Governance v2 report cases — attach budget remaining and forecast fields to Cost Intelligence reports. |

## Case formats

`checksums.json`:
```json
{ "id": "iban-valid", "scanner": "IBAN", "text": "...NL91ABNA0417164300...", "shouldMatch": true }
```

`detection.json` (PII):
```json
{ "id": "email-and-iban", "text": "...", "expectedTypes": ["EMAIL", "IBAN"] }
```

`scanner` / entity-type names are the canonical uppercase identifiers:
`EMAIL, IBAN, BSN, CREDIT_CARD, PHONE, IP_ADDRESS, SSN, SECRET`.

`license/detection.json`:
```json
{ "id": "mit-header", "text": "...", "expectedLicenses": ["MIT"] }
```

`expectedLicenses` are SPDX ids sorted ascending:
`Apache-2.0, BSD-3-Clause, GPL-2.0, GPL-3.0, MIT, MPL-2.0`.

`policy-packs/manifest.json`:
```json
{
  "builtinPacks": [{ "id": "gavio.fintech", "detectorEntityTypes": ["SWIFT_BIC"] }],
  "customRulePack": {
    "rules": [{ "entityType": "EMPLOYEE_ID", "pattern": "\\bEMP-[0-9]{6}\\b" }],
    "cases": [{ "text": "EMP-123456", "expectedTypes": ["EMPLOYEE_ID"] }]
  }
}
```

`policy-packs/catalog.json`:
```json
{
  "catalogNames": ["core", "finance"],
  "overrideCase": {
    "pack": "finance",
    "overrides": { "detectors": { "account_number": { "severity": "critical" } } }
  },
  "domainCases": [{ "pack": "healthcare", "expectedTypes": ["MEDICAL_RECORD_NUMBER"] }]
}
```

`control-plane/runtime-config.json`:
```json
{
  "runtimeConfigCase": {
    "policySource": "project:prod-support",
    "expected": { "projectId": "proj_support", "policyPack": "support" }
  },
  "offlineCacheCase": { "firstLoad": "control_plane", "secondLoad": "cache" }
}
```

`trust/production-trust-bundle.json`:
```json
{
  "bundleId": "trust-prod-support-2026-07-12",
  "privacy": { "contentMode": "metadata_only", "containsRawContent": false },
  "evidence": {
    "auditChain": { "verified": true },
    "runtimeEvents": { "contentFree": true }
  },
  "bundleHash": "sha256:..."
}
```

`integrations/catalog.json`:
```json
{
  "recipes": [
    {
      "id": "litellm",
      "category": "gateway",
      "gavioSurfaces": ["metadata", "runtime_events"],
      "docsPath": "docs/integrations/litellm.md",
      "examplePath": "examples/integrations/litellm/recipe.py"
    }
  ]
}
```

`integrations/adapters.json`:
```json
{
  "source": { "traceId": "trace_123", "type": "trace.end", "data": { "status": "ok" } },
  "metadata": { "tenant": "acme", "prompt": "synthetic prompt text" },
  "forbiddenStrings": ["synthetic prompt text"],
  "adapters": [{ "id": "litellm", "expects": [{ "path": ["payload", "completionKwargs"] }] }]
}
```

`inspector/cost-report.json`:
```json
{
  "id": "cost-report-groups-by-tenant",
  "groupBy": "tenant",
  "summaries": [{ "traceId": "t", "tenant": "acme", "costUsd": 0.01 }],
  "expected": { "total": { "costUsd": 0.01 } }
}
```

`cost-governance/budget-decisions.json`:
```json
{
  "id": "block-on-hard-limit",
  "policy": { "id": "tenant-daily", "scopeType": "tenant", "limitUsd": 1.0 },
  "currentSpendUsd": 0.95,
  "requestCostUsd": 0.1,
  "expected": { "allowed": false, "action": "block" }
}
```

`runtime-events/export-redaction.json`:
```json
{
  "contentKeys": ["messages", "content", "diff"],
  "event": { "type": "interceptor.before.end", "data": { "diff": { "...": "..." } } },
  "expectedData": { "name": "pii_guard", "mutated": true }
}
```

`otel/spans.json`:
```json
{
  "events": [{ "type": "trace.start", "data": { "provider": "openai" } }],
  "expected": { "spanNames": ["gavio.request"] }
}
```

`prompts/registry-evals.json`:
```json
{
  "templates": [{ "id": "support.reply", "messages": [{ "content": "{{ topic }}" }] }],
  "suite": { "cases": [{ "templateId": "support.reply", "assertions": [] }] }
}
```

`tool-runtime/permissions.json`:
```json
{
  "tools": {
    "permissions": ["read.billing"],
    "definitions": [{ "name": "lookup_invoice", "permissions": ["read.billing"] }],
    "calls": [{ "id": "invoice-1", "name": "lookup_invoice", "result": {} }]
  },
  "expected": { "first_action": "allow" }
}
```

## Runners (one per SDK)

| SDK | Test that consumes these vectors |
|---|---|
| Python | `packages/gavio-py/tests/unit/test_vectors.py` · `packages/gavio-py/tests/unit/test_policy_packs.py` · `packages/gavio-py/tests/unit/test_inspector_agentic.py` · `packages/gavio-py/tests/unit/test_runtime_exporters.py` · `packages/gavio-py/tests/unit/test_otel_exporter.py` · `packages/gavio-py/tests/unit/test_cost_governance_v2.py` · `packages/gavio-py/tests/unit/test_prompt_registry_evals.py` · `packages/gavio-py/tests/unit/test_tool_runtime.py` · `packages/gavio-py/tests/unit/test_control_plane.py` · `packages/gavio-py/tests/unit/test_trust.py` · `packages/gavio-py/tests/unit/test_integrations.py` |
| JavaScript | `packages/gavio-js/tests/unit/test-vectors.test.ts` · `packages/gavio-js/tests/unit/policy-packs.test.ts` · `packages/gavio-js/tests/unit/inspector-api-vectors.test.ts` · `packages/gavio-js/tests/unit/runtime-exporters.test.ts` · `packages/gavio-js/tests/unit/otel-exporter.test.ts` · `packages/gavio-js/tests/unit/cost-governance-v2.test.ts` · `packages/gavio-js/tests/unit/prompt-registry-evals.test.ts` · `packages/gavio-js/tests/unit/tool-runtime.test.ts` · `packages/gavio-js/tests/unit/control-plane.test.ts` · `packages/gavio-js/tests/unit/trust.test.ts` · `packages/gavio-js/tests/unit/integrations.test.ts` |
| Java | `packages/gavio-java/gavio-interceptor-pii/src/test/java/io/gavio/vectors/TestVectorsTest.java` (PII) · `packages/gavio-java/gavio-interceptor-pii/src/test/java/io/gavio/interceptors/pii/policy/PolicyPackTest.java` (policy packs) · `packages/gavio-java/gavio-interceptor-guardrails/src/test/java/io/gavio/vectors/LicenseVectorsTest.java` (license) · `packages/gavio-java/gavio-core/src/test/java/io/gavio/ToolRuntimeInterceptorTest.java` (Tool Runtime) · `packages/gavio-java/gavio-core/src/test/java/io/gavio/inspector/InspectorApiVectorsTest.java` (Inspector) · `packages/gavio-java/gavio-core/src/test/java/io/gavio/exporters/RuntimeExporterTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/exporters/OtelSpanExporterTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/prompts/PromptRegistryEvalTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/trust/ProductionTrustTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/integrations/IntegrationCatalogTest.java` · `packages/gavio-java/gavio-interceptor-governance/src/test/java/io/gavio/interceptors/governance/CostGovernanceV2Test.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/ControlPlaneClientTest.java` |

## Ground truth

Expected values are verified against the **Python reference implementation**.
All synthetic — no real PII appears in any vector.
