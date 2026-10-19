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
| [`integrations/ecosystem-trust.json`](./integrations/ecosystem-trust.json) | Ecosystem Trust Package cases — verify generated compatibility matrix rows, adapter coverage, metadata-only privacy boundaries, and sample production app coverage across SDKs. |
| [`platform-runtime/profile.json`](./platform-runtime/profile.json) | Platform Runtime Profile cases — verify deterministic profile hashing, metadata-only posture, required runtime surfaces, readiness score, and gap codes. |
| [`control-plane/enterprise-admin-v2.json`](./control-plane/enterprise-admin-v2.json) | Enterprise Admin v2 cases — verify OIDC/SAML-lite metadata redaction, scoped admin-key behavior, rollout approval gates, audit export privacy, and retention apply semantics. |
| [`workflow/release.json`](./workflow/release.json) | Platform Workflow Release cases (`F-DX-13`) — `canonicalJsonCases` pin the cross-SDK canonical JSON form (key order by code point, integral numbers, escapes); `hashCases` pin `platformWorkflowReleaseHash` (environment-specific `evals[*].runner` / `source` excluded); `releaseCases` write `files` plus `manifest` to a temp directory, run the file-based release builder, strip the environment fields, and compare `passed`, `reasons`, and the whole artifact by canonical JSON. |
| [`runtime-events/export-redaction.json`](./runtime-events/export-redaction.json) | Runtime exporter privacy contract (F-EXP-01) — verify metadata-only export strips content-bearing event fields while preserving trace and decision metadata. |
| [`otel/spans.json`](./otel/spans.json) | OTel bridge cases (F-OBS-07) — map runtime events into OpenTelemetry-style spans with parent links, status, timestamps, attributes, and metadata-only privacy. |
| [`prompts/registry-evals.json`](./prompts/registry-evals.json) | Prompt Registry + Evals cases (`F-EVAL-01/02`) — render versioned prompt templates, validate missing variables, attach metadata-only lineage, score eval cases, and keep raw outputs out of reports. |
| [`tool-runtime/permissions.json`](./tool-runtime/permissions.json) | Tool Runtime v2 permissions and approval cases — registry-backed permission checks, destructive approval gates, provenance requirements, and MCP metadata capture. |
| [`tool-runtime/replay.json`](./tool-runtime/replay.json) | Tool Runtime v2 replay cases — reconstruct deterministic runtime decisions from stored tool-call records. |
| [`tool-calling/openai.json`](./tool-calling/openai.json) | Normalised tool calling (F-ADP-03), OpenAI Chat Completions — `requestCases` (normalised request → native payload: tools, `toolChoice`, assistant `toolCalls`, tool-role results), `responseCases` (native response → `content`/`toolCalls`/`finishReason`/`usage`), and `errorCases` (malformed arguments). OpenRouter reuses the same shape and must pass this file unchanged. |
| [`tool-calling/azure.json`](./tool-calling/azure.json) | Azure OpenAI tool calling — same shape as OpenAI, request body without `model`, extra content-filter response members ignored. |
| [`tool-calling/anthropic.json`](./tool-calling/anthropic.json) | Anthropic Messages tool calling — `tool_use` / `tool_result` blocks, `tool_choice` mapping, consecutive tool results merged into one user turn. |
| [`tool-calling/gemini.json`](./tool-calling/gemini.json) | Gemini tool calling — `functionCall` / `functionResponse` parts, synthetic `call_<index>_<name>` ids, `toolConfig` mapping, `requestErrorCases` for unmatched results. |
| [`tool-calling/ollama.json`](./tool-calling/ollama.json) | Ollama `/api/chat` tool calling per the documented shape (object arguments, no ids, `tool_name` on results, no `tool_choice`). Not exercised against a live server. |
| [`tool-calling/arguments-hash.json`](./tool-calling/arguments-hash.json) | `ToolCall.arguments` hash — canonical JSON (sorted keys, no whitespace, UTF-8) and its SHA-256, the only form of tool arguments an audit record keeps. |
| [`loop-guard/decisions.json`](./loop-guard/decisions.json) | Loop guard cases (F-REL-09) — `decisionCases` (policy + run state → `LoopGuardDecision`: each of the four limits, boundaries, breach order, stop/fallback/dry_run), `runCases` (a sequence of calls: decide before each, record the ones that went through, compare per-call actions, steps, cost and elapsed time), `rootRunCases` (root run id resolution and the bounded least-recently-used map) and `policyErrorCases` (invalid policies are a configuration error). Expected values are written by hand from the spec, not generated by an SDK. |
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

`control-plane/enterprise-admin-v2.json`:
```json
{
  "featureId": "F-ADMIN-02",
  "adminKeyCase": {
    "tokenPrefix": "gav_admin_",
    "scopes": ["audit:export", "policy:approve"]
  },
  "rolloutApprovalCase": {
    "beforeApprovalStatus": "pending_approval",
    "afterApprovalStatus": "active"
  }
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

`integrations/ecosystem-trust.json`:
```json
{
  "since": "2.7.0",
  "productionApps": [{ "id": "gateway-observability-eval", "covers": ["litellm"] }],
  "cases": [{ "id": "litellm", "adapterPayload": true, "sampleApps": ["gateway-observability-eval"] }]
}
```

The generated compatibility matrix is checked in at
`docs/integrations/compatibility-matrix.json` and must stay in sync:

```bash
node scripts/gen-ecosystem-trust-matrix.mjs --check
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

`workflow/release.json`:
```json
{
  "canonicalJsonCases": [{ "id": "integral-floats-are-integers", "value": [1.0, -0.0], "expected": "[1,0]" }],
  "hashCases": [{ "id": "environment-fields-are-excluded", "artifact": { "...": "..." }, "expectedHash": "sha256:..." }],
  "releaseCases": [
    {
      "id": "ready-release",
      "files": { "suite.json": { "...": "..." }, "policy-pack/manifest.json": { "...": "..." } },
      "manifest": { "workflowId": "support-platform-release", "evals": [{ "suite": "suite.json" }] },
      "forbiddenStrings": ["raw release notes must not persist"],
      "expected": { "passed": true, "reasons": [], "artifact": { "workflowHash": "sha256:..." } }
    }
  ]
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

`tool-calling/*.json` (provider files):
```json
{
  "provider": "openai",
  "requestCases": [{
    "id": "history-single-call",
    "request": { "model": "gpt-4o", "provider": "openai", "messages": ["..."], "tools": ["..."], "toolChoice": "auto", "options": { "temperature": 0.2, "maxTokens": 256 } },
    "expectedPayload": { "model": "gpt-4o", "messages": ["..."], "temperature": 0.2, "max_tokens": 256 }
  }],
  "requestErrorCases": [{ "id": "...", "request": {}, "expectedError": "Configuration" }],
  "responseCases": [{
    "id": "parallel-calls",
    "providerResponse": { "choices": ["..."] },
    "expected": { "content": "", "toolCalls": [{ "id": "call_1", "name": "get_weather", "arguments": {} }], "finishReason": "tool_calls", "usage": { "promptTokens": 41, "completionTokens": 25, "totalTokens": 66 }, "modelVersion": "gpt-4o-2024-08-06" }
  }],
  "errorCases": [{ "id": "invalid-json-arguments", "providerResponse": {}, "expectedError": "MalformedToolCall" }]
}
```

Each SDK's provider adapter exposes two pure functions — build the native request body from a normalised request, and parse a native response body — so no case needs a network. A `requestCase` builds a request from `request` (messages use the spec's camelCase `toolCalls` / `toolCallId` / `isError`; an SDK may spell them natively as long as it produces the same payload) and compares the adapter's body to `expectedPayload` exactly, including the compact `arguments` string OpenAI-style bodies carry. A `responseCase` compares the parsed `content`, `toolCalls`, `finishReason`, `usage`, and `modelVersion` (`null` when the provider reports none) to `expected`. `expectedError` is `Configuration` (invalid request) or `MalformedToolCall` (invalid provider tool call); an error message must never contain the tool arguments. Every `request` validates against `spec/GavioRequest.schema.json`, and every `expected` against `spec/GavioResponse.schema.json`.

`tool-calling/arguments-hash.json`:
```json
{ "id": "key-order-does-not-matter-a", "arguments": { "a": 2, "b": 1 }, "expectedCanonical": "{\"a\":2,\"b\":1}", "expectedHash": "<sha256 hex>" }
```

`loop-guard/decisions.json` (semantics: `spec/README.md`, 'Loop guard'):
```json
{
  "decisionCases": [{ "id": "max-steps-reached-refuses", "policy": { "id": "agent-loop", "action": "stop", "maxSteps": 25 }, "state": { "steps": 25, "costUsd": 0.0, "elapsedSeconds": 0.0, "toolCalls": [{ "name": "search", "argumentsHash": "...", "count": 2 }] }, "expected": { "allowed": false, "action": "stop", "breached": "max_steps" }, "expectedAbsent": ["targetModel"] }],
  "runCases": [{ "id": "max-steps-stops-a-runaway-run", "policy": { "id": "agent-loop", "action": "stop", "maxSteps": 3 }, "calls": [{ "atSeconds": 0, "costUsd": 0.01, "toolCalls": [{ "name": "search", "arguments": { "q": "cats" } }] }], "expectedActions": ["allow"], "expectedBreached": [null], "expectedSteps": [0], "expectedCostUsd": [0.0], "expectedElapsedSeconds": [0.0] }],
  "rootRunCases": [{ "id": "...", "capacity": 2, "calls": [{ "traceId": "t2", "parentTraceId": "t1", "metadata": { "runId": "run-A" } }], "expectedRunIds": ["t1"] }],
  "policyErrorCases": [{ "id": "max-steps-below-one", "policy": { "id": "agent-loop", "action": "stop", "maxSteps": 0 }, "expectedError": "Configuration" }]
}
```

A `decisionCase` checks every key in `expected` against the decision (and that every key in `expectedAbsent` is missing); a `runCase` plays its `calls` in order — elapsed time is `atSeconds` minus the first call's, the first call starts the run, a call the guard refuses (`stop`) is not recorded — and compares the per-call arrays; `toolCalls[].arguments` are hashed with the `ToolCall.arguments_hash` rule. A `rootRunCase` resolves its `calls` in order against one resolver of the given `capacity` (default 10,000).

## Runners (one per SDK)

| SDK | Test that consumes these vectors |
|---|---|
| Python | `packages/gavio-py/tests/unit/test_vectors.py` · `packages/gavio-py/tests/unit/test_policy_packs.py` · `packages/gavio-py/tests/unit/test_inspector_agentic.py` · `packages/gavio-py/tests/unit/test_runtime_exporters.py` · `packages/gavio-py/tests/unit/test_otel_exporter.py` · `packages/gavio-py/tests/unit/test_cost_governance_v2.py` · `packages/gavio-py/tests/unit/test_prompt_registry_evals.py` · `packages/gavio-py/tests/unit/test_tool_runtime.py` · `packages/gavio-py/tests/unit/test_control_plane.py` · `packages/gavio-py/tests/unit/test_trust.py` · `packages/gavio-py/tests/unit/test_integrations.py` · `packages/gavio-py/tests/unit/test_tool_calling.py` · `packages/gavio-py/tests/unit/test_loop_guard_vectors.py` · `packages/gavio-py/tests/unit/test_platform_workflow.py` |
| JavaScript | `packages/gavio-js/tests/unit/test-vectors.test.ts` · `packages/gavio-js/tests/unit/policy-packs.test.ts` · `packages/gavio-js/tests/unit/inspector-api-vectors.test.ts` · `packages/gavio-js/tests/unit/runtime-exporters.test.ts` · `packages/gavio-js/tests/unit/otel-exporter.test.ts` · `packages/gavio-js/tests/unit/cost-governance-v2.test.ts` · `packages/gavio-js/tests/unit/prompt-registry-evals.test.ts` · `packages/gavio-js/tests/unit/tool-runtime.test.ts` · `packages/gavio-js/tests/unit/control-plane.test.ts` · `packages/gavio-js/tests/unit/trust.test.ts` · `packages/gavio-js/tests/unit/integrations.test.ts` · `packages/gavio-js/tests/unit/platform-workflow.test.ts` · `packages/gavio-js/tests/unit/tool-calling.test.ts` |
| Java | `packages/gavio-java/gavio-interceptor-pii/src/test/java/io/gavio/vectors/TestVectorsTest.java` (PII) · `packages/gavio-java/gavio-interceptor-pii/src/test/java/io/gavio/interceptors/pii/policy/PolicyPackTest.java` (policy packs) · `packages/gavio-java/gavio-interceptor-guardrails/src/test/java/io/gavio/vectors/LicenseVectorsTest.java` (license) · `packages/gavio-java/gavio-core/src/test/java/io/gavio/ToolRuntimeInterceptorTest.java` (Tool Runtime) · `packages/gavio-java/gavio-core/src/test/java/io/gavio/inspector/InspectorApiVectorsTest.java` (Inspector) · `packages/gavio-java/gavio-core/src/test/java/io/gavio/exporters/RuntimeExporterTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/exporters/OtelSpanExporterTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/prompts/PromptRegistryEvalTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/trust/ProductionTrustTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/integrations/IntegrationCatalogTest.java` · `packages/gavio-java/gavio-interceptor-governance/src/test/java/io/gavio/interceptors/governance/CostGovernanceV2Test.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/ControlPlaneClientTest.java` · `packages/gavio-java/gavio-core/src/test/java/io/gavio/platform/PlatformWorkflowTest.java` (canonical JSON, hash, policy-free release) · `packages/gavio-java/gavio-interceptor-pii/src/test/java/io/gavio/vectors/PlatformWorkflowVectorsTest.java` (full workflow release) |

## Ground truth

Expected values are verified against the **Python reference implementation**.
All synthetic — no real PII appears in any vector.
