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
| [`LoopGuardPolicy.schema.json`](./LoopGuardPolicy.schema.json) | Loop guard limits for one root run (F-REL-09): max steps, cost, wall time and repeated tool calls, and what happens on a breach |
| [`LoopGuardDecision.schema.json`](./LoopGuardDecision.schema.json) | Loop guard verdict on one provider call (F-REL-09): allow, stop, fallback or dry_run, which limit was reached, and the run's counts |
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

## Loop guard (F-REL-09)

A loop guard stops a runaway agent: it keeps a small state per **root run** and, before every
provider call, compares that state with a [`LoopGuardPolicy`](./LoopGuardPolicy.schema.json).
The verdict is a [`LoopGuardDecision`](./LoopGuardDecision.schema.json); it is also recorded on the
audit record (`AuditRecord.loopGuard`, omitted when no guard is installed).

- **Root run id.** The key every call is counted under, resolved per request in this order:
  `metadata.runId` (Python also accepts `metadata.run_id`) when it is a non-empty string; else the
  root of the `parentTraceId` chain; else the request's own `traceId`. The chain root comes from a
  bounded least-recently-used map of `traceId -> root run id` (default capacity 10,000): resolving
  a request reads its parent's entry (refreshing it) and stores its own, evicting the least
  recently used entry beyond the capacity. A `parentTraceId` the map does not know — evicted, or
  from another process — is treated as the root itself, so pass `metadata.runId` when a run spans
  processes or outlives the map. Calls that carry neither a run id nor a parent are separate
  runs: an agent loop must pass one of them.
- **State.** Per run: the number of provider calls made (`steps`), their recorded cost, the time of
  the first call, and for every tool call the responses asked for a count by
  `(name, argumentsHash)` in first-seen order — the hash is `ToolCall.arguments_hash` from
  F-ADP-03, so repeats are detected without keeping the arguments. After an allowed,
  fallback or dry_run call the run records `steps + 1`, the response's cost, and one count per
  tool call in the response (a parallel response asking for the same call twice counts twice).
  A refused (`stop`) call changes nothing. Run state is bounded and idle-expiring like the
  root-run map (default 10,000 runs, one hour idle).
- **Evaluation** (pure; vectors in `test-vectors/loop-guard/`). A limit is reached when
  `steps >= maxSteps`, `costUsd >= maxCostUsd`, `elapsedSeconds >= maxWallTimeSeconds`, or any
  counted tool call has `count >= maxRepeatedToolCalls` (cost and elapsed time are compared after
  rounding to 8 decimal places); a null limit is never reached. `breaches`
  lists every limit reached in the order `max_steps`, `max_cost`, `max_wall_time`,
  `repeated_tool_call`, and `breached` is the first. With no breach the action is `allow`;
  otherwise it is the policy's `action` (`stop` refuses the call and raises, `fallback` lets it
  through on `fallbackModel`, `dry_run` records the breach and changes nothing). The decision's
  `costUsd` and `elapsedSeconds` are the rounded values. An invalid policy is a configuration error:
  `maxSteps` below 1, `maxRepeatedToolCalls` below 2, a negative `maxCostUsd`, a
  `maxWallTimeSeconds` that is not positive, an unknown `action`, or `fallback` without a
  `fallbackModel`.
- **Privacy.** Decisions and the audit entry hold counts, a cost and a time only — never prompts,
  responses or tool arguments.

## Cross-SDK conformance

Behavioural parity (not just shape) is enforced by the shared cases in
[`../test-vectors/`](../test-vectors/), which every SDK loads and runs in its own
test suite. A change to detection logic, checksums, or redaction that breaks one
SDK breaks its test-vector run.

## Versioning

Schemas are versioned in the `$id` (`.v1.json`). The `AuditRecord.schemaVersion`
field (`"1.0"`) is embedded in every record so consumers can migrate.
