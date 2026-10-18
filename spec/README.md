# Gavio Specification — Canonical Data Model

These JSON Schemas (Draft 2020-12) are the **single source of truth** for
Gavio's data model. Every SDK — Python, Java, JavaScript — implements types that
serialise to shapes valid against these schemas.

| Schema | Describes |
|---|---|
| [`GavioRequest.schema.json`](./GavioRequest.schema.json) | The provider-agnostic request |
| [`GavioResponse.schema.json`](./GavioResponse.schema.json) | The enriched response |
| [`AuditRecord.schema.json`](./AuditRecord.schema.json) | The per-request audit entry (hashes only) |
| [`GavioRuntimeEvent.schema.json`](./GavioRuntimeEvent.schema.json) | The public runtime event/export envelope |
| [`GavioOtelSpan.schema.json`](./GavioOtelSpan.schema.json) | OpenTelemetry-style span JSON produced from runtime events |
| [`PromptTemplate.schema.json`](./PromptTemplate.schema.json) | Versioned prompt template registered in the Prompt Registry |
| [`PromptManifest.schema.json`](./PromptManifest.schema.json) | Signed file-backed Prompt Registry manifest with semver template versions |
| [`EvalReport.schema.json`](./EvalReport.schema.json) | Metadata-safe Prompt Registry eval report |
| [`ToolDefinition.schema.json`](./ToolDefinition.schema.json) | Tool Runtime v2 registry entry with schemas, permissions, risk, and MCP metadata |
| [`ToolPermission.schema.json`](./ToolPermission.schema.json) | Tool Runtime v2 permission scope |
| [`ToolCallRecord.schema.json`](./ToolCallRecord.schema.json) | Tool Runtime v2 observed tool-call input/output record |
| [`ToolApproval.schema.json`](./ToolApproval.schema.json) | Tool Runtime v2 approval record |
| [`ToolCall.schema.json`](./ToolCall.schema.json) | Normalised model-requested tool call (F-ADP-03): `id`, `name`, parsed `arguments`; the unit behind `GavioResponse.toolCalls` and assistant `message.toolCalls` |
| [`ToolResult.schema.json`](./ToolResult.schema.json) | Normalised tool outcome (F-ADP-03): `toolCallId`, `content`, optional `isError`; carried as a `role: "tool"` message |
| [`PolicyPackManifest.schema.json`](./PolicyPackManifest.schema.json) | Signed domain Policy Pack catalog manifest |
| [`PolicyRule.schema.json`](./PolicyRule.schema.json) | One detector/rule entry inside a Policy Pack manifest |
| [`PolicyPackSignature.schema.json`](./PolicyPackSignature.schema.json) | Deterministic Policy Pack manifest signature metadata |
| [`ControlPlaneRuntimeConfig.schema.json`](./ControlPlaneRuntimeConfig.schema.json) | Runtime config fetched by SDKs from the optional self-hosted control plane |
| [`ControlPlaneEvent.schema.json`](./ControlPlaneEvent.schema.json) | Metadata-only audit/event records searchable by trace, tenant, feature, provider, model, risk, and time |
| [`ProductionTrustBundle.schema.json`](./ProductionTrustBundle.schema.json) | Metadata-only production release evidence bundle with audit-chain, runtime-event, control, benchmark, and document pointers |
| [`IntegrationRecipe.schema.json`](./IntegrationRecipe.schema.json) | Ecosystem integration compatibility recipe with role boundaries, supported Gavio surfaces, docs path, and example path |
| [`IntegrationAdapterPayload.schema.json`](./IntegrationAdapterPayload.schema.json) | Dependency-light ecosystem adapter payload envelope for gateway, eval, observability, and framework tools |
| [`PlatformRuntimeProfile.schema.json`](./PlatformRuntimeProfile.schema.json) | Metadata-only platform-grade runtime profile with enabled surfaces, evidence, readiness score, and deterministic gaps |
| [`PlatformWorkflowRelease.schema.json`](./PlatformWorkflowRelease.schema.json) | Metadata-only platform workflow release artifact (prompts, evals, policy packs, trust, runtime profile) with the canonical-JSON `workflowHash`, plus the input manifest shape |
| [`BudgetPolicy.schema.json`](./BudgetPolicy.schema.json) | Cost Governance v2 scoped budget policy |
| [`BudgetDecision.schema.json`](./BudgetDecision.schema.json) | Cost Governance v2 allow/warn/block/fallback decision |
| [`CostReport.schema.json`](./CostReport.schema.json) | Cost Governance v2 spend, overhead, savings, and budget report |
| [`PiiMatch.schema.json`](./PiiMatch.schema.json) | A single detected PII entity |
| [`InterceptorResult.schema.json`](./InterceptorResult.schema.json) | One interceptor's observable outcome |

## Field naming

The wire format is **camelCase** (`agentId`, `parentTraceId`, `costUsd`), which
the Java and JavaScript SDKs use natively. The Python SDK exposes the same
fields in **snake_case** (`agent_id`, `parent_trace_id`, `cost_usd`) per PEP 8;
they map 1:1.

## Tool calling (F-ADP-03)

`GavioRequest` gains optional `tools` (an array of [`ToolDefinition`](./ToolDefinition.schema.json))
and `toolChoice`; `GavioResponse` gains optional `toolCalls` and `finishReason`;
`AuditRecord` gains optional `toolCalls`. Every member is optional, so documents
written before F-ADP-03 stay valid.

- **Messages.** An assistant turn that calls tools is
  `{"role": "assistant", "content": "", "toolCalls": [ToolCall, ...]}` — `content` stays a
  string (empty when the model only called tools). A tool result is
  `{"role": "tool", "toolCallId": "...", "content": "..."}` with optional `name` and
  `isError`. Python spells these keys `tool_calls`, `tool_call_id`, `is_error`.
- **`toolChoice`.** `"auto" | "none" | "required" | {"name": "<tool>"}`. Ignored when
  `tools` is empty. Ollama's `/api/chat` has no tool-choice parameter, so its adapter
  rejects anything other than `auto`.
- **Call ids.** Provider ids are kept verbatim. Gemini and Ollama send none, so the adapter
  synthesises `call_<index>_<name>` (zero-based position in the response), which is stable
  across SDKs and replays.
- **Arguments.** `ToolCall.arguments` is always a JSON object. OpenAI sends a JSON string:
  adapters parse it on the way in and re-serialise it compactly (insertion order, no
  whitespace) on the way out. Unparseable or non-object arguments are a provider error.
- **Arguments hash.** Lowercase hex SHA-256 of the canonical JSON of `arguments` (keys
  sorted at every depth, no whitespace, UTF-8, non-ASCII not escaped). Audit records store
  this hash and the tool name — never the arguments. Use integers and strings in
  arguments that you intend to hash: float formatting differs across languages.
- **Privacy.** PII and secret scanning covers tool-call argument string values and
  tool-role message content, before the provider payload is built. In `REDACT` mode,
  placeholders are restored in `GavioResponse.toolCalls[*].arguments` like in `content`.

## Cross-SDK conformance

Behavioural parity (not just shape) is enforced by the shared cases in
[`../test-vectors/`](../test-vectors/), which every SDK loads and runs in its own
test suite. A change to detection logic, checksums, or redaction that breaks one
SDK breaks its test-vector run.

## Versioning

Schemas are versioned in the `$id` (`.v1.json`). The `AuditRecord.schemaVersion`
field (`"1.0"`) is embedded in every record so consumers can migrate.
