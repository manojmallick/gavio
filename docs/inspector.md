# Inspector

**Added in v0.6.0** · Feature IDs: `F-DX-09` (core) · `F-DX-10` (UI)
**Extended in v0.7.0** · `F-OBS-10` (agent DAG) · `F-DX-11` (replay) · `F-DX-08` (production dashboard) · `F-DX-12` (test-case export)
**Extended in v0.11.0** · `F-COST-01/02/04` (cost attribution, reports, scoped budgets)

The Gavio Inspector is an embedded, zero-dependency dev-time visualizer. While
a request moves through the interceptor chain, the gateway emits span events —
which interceptor fired, in what order, what each one changed, how long each
took, what the provider returned. A bounded ring buffer assembles them into
traces, and a localhost HTTP server renders them in a self-contained web UI at
**http://127.0.0.1:7411**.

It is **off by default** — dev mode does *not* enable it implicitly.

## Enable it

```python
# Python
gw = Gateway.builder().dev_mode(True).inspect(True).build()
```

```typescript
// TypeScript
const gw = new Gateway({ devMode: true, inspect: true })
```

```java
// Java
Gateway gw = Gateway.builder().devMode(true).inspect(true).build();
```

Or without a code change: `GAVIO_INSPECT=1` (plus `GAVIO_INSPECT_PORT` /
`GAVIO_INSPECT_MODE`).

## What you get

- Live trace list (SSE) with status, latency, cost, PII and cache badges
- A waterfall per trace — every interceptor span with duration + the provider call
- PII redaction diffs (original vs redacted, side by side)
- Decision records — cache, cost-router, or your own `ctx.inspect(key, value)`
- Pipeline view with ordering lints (e.g. audit registered before pii_guard)
- Cost Intelligence — group spend by tenant, feature, endpoint, user, workflow,
  tool, model, provider, session, agent or middleware chain

## Capture modes

| Mode | Intended for | Content captured |
|---|---|---|
| `full` | Local dev | Messages, response, diffs (secrets always masked). Outside dev mode requires `unsafe_content_capture_ack`. |
| `redacted` | Staging | Post-redaction content only |
| `metadata` | Production | No content ever — fields structurally absent |

The server binds `127.0.0.1` by default; non-loopback binds require an
`auth_token` (Bearer). The JSON API (`/api/health`, `/api/pipeline`,
`/api/traces`, `/api/traces/{id}`, `/api/stream`) is identical in all three
SDKs — contract in [`spec/InspectorEvent.schema.json`](../spec/InspectorEvent.schema.json),
parity enforced by [`test-vectors/inspector/`](../test-vectors/inspector/).

## Agentic & production mode (v0.7.0)

- **Agent call graphs & sessions** (`F-OBS-10`) — `GET /api/dag?root=|session_id=`
  builds the multi-agent DAG from `parent_trace_id`/`agent_id` with subtree
  cost/latency/status rollups; `GET /api/sessions` aggregates per session.
  DAG and Sessions tabs in the UI.
- **Trace replay & edit-resend** (`F-DX-11`) — `POST /api/replay` re-fires a
  captured request through the live gateway (full chain, never bypassed),
  optionally with edited messages/model. `full` mode only — 403 otherwise.
- **RED stats** — `GET /api/stats?group_by=&since=`: rate, error %, latency
  p50/p95/p99, tokens, cost, average cost/request, retry count, retry overhead,
  cache hit-rate, cache savings, PII counts and drift counts. `group_by` accepts
  `provider`, `model`, `agent_id`, `session_id`, `feature`, `tenant`, `user`,
  `endpoint`, `environment`, `workflow`, `tool`, or `middleware_chain`.
- **Cost report** (`F-COST-02`) — `GET /api/cost-report?group_by=&since=`
  returns the same aggregate shape as stats plus `topSpend` lists for every
  supported cost dimension.
- **Export as test case** (`F-DX-12`) —
  `GET /api/traces/{id}/export?format=test-vector|testkit-py|testkit-java|testkit-js`;
  PII values replaced with synthetic fixtures before export.
- **Cost simulator** — `GET /api/simulate-cost?trace_id=&model=` recosts a
  trace under a different model via the pricing table.
- **Read-only dashboard** (`F-DX-08`, Python CLI) — write audits with
  `AuditInterceptor(sink=JsonlSink("audit.jsonl"), hash_chain=True)`, then
  `gavio inspect --store audit.jsonl` serves the metadata-mode dashboard with
  search-by-content-hash (`/api/traces?q=`) and the hash-chain verifier
  (`/api/chain/verify`, `F-OBS-02` surfaced) — no gateway, no content, no replay.

Fleet extras: a prebuilt [Grafana dashboard](./grafana/gavio-dashboard.json)
over the Prometheus metrics and the
[InspectorEvent → OpenTelemetry mapping](./otel-mapping.md).

## Cost Attribution (v0.11.0)

Requests can carry attribution metadata without adding new constructor fields:

```json
{
  "metadata": {
    "costDimensions": {
      "tenant": "acme",
      "feature": "claims",
      "endpoint": "/chat",
      "environment": "prod",
      "workflow": "triage",
      "tool": "search"
    }
  }
}
```

Flat aliases are accepted too (`tenant`, `tenantId`, `feature_id`, `user_id`,
`env`, `route`, etc.). The Inspector copies only scalar labels into
`trace.start.data.costDimensions`; prompt and response content never enter
metadata mode.
