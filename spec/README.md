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
| [`EvalReport.schema.json`](./EvalReport.schema.json) | Metadata-safe Prompt Registry eval report |
| [`ToolDefinition.schema.json`](./ToolDefinition.schema.json) | Tool Runtime v2 registry entry with schemas, permissions, risk, and MCP metadata |
| [`ToolPermission.schema.json`](./ToolPermission.schema.json) | Tool Runtime v2 permission scope |
| [`ToolCallRecord.schema.json`](./ToolCallRecord.schema.json) | Tool Runtime v2 observed tool-call input/output record |
| [`ToolApproval.schema.json`](./ToolApproval.schema.json) | Tool Runtime v2 approval record |
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

## Cross-SDK conformance

Behavioural parity (not just shape) is enforced by the shared cases in
[`../test-vectors/`](../test-vectors/), which every SDK loads and runs in its own
test suite. A change to detection logic, checksums, or redaction that breaks one
SDK breaks its test-vector run.

## Versioning

Schemas are versioned in the `$id` (`.v1.json`). The `AuditRecord.schemaVersion`
field (`"1.0"`) is embedded in every record so consumers can migrate.
