# Inspector

**Added in v0.6.0** · Feature IDs: `F-DX-09` (core) · `F-DX-10` (UI)
**Extended in v0.7.0** · `F-OBS-10` (agent DAG) · `F-DX-11` (replay) · `F-DX-08` (production dashboard) · `F-DX-12` (test-case export)

The Gavio Inspector is an embedded, zero-dependency dev-time visualizer. While
a request moves through the interceptor chain, the gateway emits span events —
which interceptor fired, in what order, what each one changed, how long each
took, what the provider returned. A bounded ring buffer assembles them into
traces, and a localhost HTTP server renders them in a self-contained web UI.

Since v0.7.0 it also renders **multi-agent call graphs and sessions**, can
**replay** any captured request, aggregates **RED stats**, **exports traces as
test cases**, and doubles as a **read-only production dashboard** over a
persisted audit store.

It is **off by default** — dev mode does *not* enable it implicitly.

## Quickstart

::: code-group

```python [Python]
from gavio import Gateway

gw = (
    Gateway.builder()
    .dev_mode(True)
    .inspect(True)          # defaults: full capture, http://127.0.0.1:7411
    .build()
)
```

```typescript [TypeScript]
import { Gateway } from 'gavio'

const gw = new Gateway({ devMode: true, inspect: true })
// defaults: full capture, http://127.0.0.1:7411
```

```java [Java]
Gateway gw = Gateway.builder()
    .devMode(true)
    .inspect(true)          // defaults: full capture, http://127.0.0.1:7411
    .build();
```

:::

Or via the environment — no code change:

```bash
GAVIO_INSPECT=1 python your_app.py     # GAVIO_INSPECT_PORT / GAVIO_INSPECT_MODE to tune
```

Open **http://127.0.0.1:7411** and send a request through the gateway. You get:

- a **live trace list** (SSE) with status, latency, cost, PII and cache badges,
- a **waterfall** per trace — every interceptor span with its duration, plus the provider call,
- the **redaction diff** — original vs redacted text, side by side, per PII match,
- **decision records** — what the cache, cost router, or your own interceptor decided,
- a **pipeline view** with ordering lints (e.g. *"audit registered before pii_guard — audit will hash unredacted prompts"*).

## Capture modes

| Mode | Intended for | Content captured |
|---|---|---|
| `full` | Local dev, debugging | Messages, response, mutation diffs (secrets always masked) |
| `redacted` | Staging | Post-redaction content only; diffs omit the original text |
| `metadata` | Production | **No content ever** — the content-bearing fields are structurally absent from every event |

Safety rails:

- `full` outside dev mode refuses to start unless you set
  `unsafe_content_capture_ack` explicitly — raw-content capture cannot be
  enabled by accident.
- Secret values (API keys, JWTs, private keys) are masked in **every** mode.
- The server binds `127.0.0.1` only; a non-loopback bind without an
  `auth_token` is a startup error, not a warning.

## JSON API

Everything the UI shows is plain JSON — same endpoints in all three SDKs.
Every response carries an `X-Gavio-Inspector-Mode` header; when `auth_token`
is set, all requests require `Authorization: Bearer <token>`.

| Endpoint | Returns |
|---|---|
| `GET /api/health` | status, SDK, version, mode, trace count |
| `GET /api/pipeline` | chain composition + ordering lints |
| `GET /api/traces?limit=N&q=` | trace summaries, chronological; `q` matches trace-id or content-hash prefixes |
| `GET /api/traces/{id}` | one assembled trace: summary + ordered events |
| `GET /api/dag?root=<id>` or `?session_id=<id>` | agent call graph with subtree cost/latency/status rollups <Badge type="tip" text="v0.7.0" /> |
| `GET /api/sessions` | sessions with trace counts, errors, agents, cost, duration <Badge type="tip" text="v0.7.0" /> |
| `GET /api/stats?group_by=&since=` | RED aggregates: rate, error %, latency p50/p95/p99, tokens, cost, cache hit-rate, PII counts <Badge type="tip" text="v0.7.0" /> |
| `POST /api/replay` | re-fires a captured trace through the live gateway (`full` mode only) <Badge type="tip" text="v0.7.0" /> |
| `GET /api/simulate-cost?trace_id=&model=` | recosts a trace under a different model <Badge type="tip" text="v0.7.0" /> |
| `GET /api/traces/{id}/export?format=` | trace as a test case (see below) <Badge type="tip" text="v0.7.0" /> |
| `GET /api/chain/verify` | walks the audit hash-chain; store mode only <Badge type="tip" text="v0.7.0" /> |
| `GET /api/stream` | live event feed (Server-Sent Events) |
| `GET /` | the bundled UI |

The event contract is canonical JSON Schema:
[`spec/InspectorEvent.schema.json`](https://github.com/manojmallick/gavio/blob/main/spec/InspectorEvent.schema.json).
Cross-SDK parity is enforced by shared
[event-sequence test vectors](https://github.com/manojmallick/gavio/tree/main/test-vectors/inspector)
that all three suites run.

## Agent call graphs & sessions <Badge type="tip" text="v0.7.0" />

Pass `agent_id`, `parent_trace_id`, and `session_id` on your gateway calls and
the **DAG** tab reconstructs the multi-agent call graph — every node shows its
own cost/latency/status plus subtree rollups (total traces, cost, latency,
errors under that agent). The **Sessions** tab groups traces by `session_id`
with per-session totals; clicking a session opens its graph.

## Trace replay & edit-resend <Badge type="tip" text="v0.7.0" />

From the trace detail (or `POST /api/replay {"traceId", "overrides"?}`) you can
re-fire any captured request through the **live gateway** — the full
interceptor chain runs again, PII guard included, never bypassed. Optionally
edit the messages or the model first ("would haiku have been good enough?").
The result opens as a new trace. Replay requires `full` capture mode — the
endpoint returns 403 otherwise, and the store-backed dashboard never replays.

## Export a trace as a test case <Badge type="tip" text="v0.7.0" />

`GET /api/traces/{id}/export?format=test-vector|testkit-py|testkit-java|testkit-js`
renders a captured trace as a shared `test-vectors/` JSON case or a runnable
`GavioTestKit` unit test in any of the three languages. Detected PII values are
replaced with the repo's synthetic fixtures (e.g. `jan@example.com`,
`NL91ABNA0417164300`) before export, so real data never lands in a test file.
Debug → regression test in one click. Requires `full` or `redacted` mode.

## Cost simulator <Badge type="tip" text="v0.7.0" />

`GET /api/simulate-cost?trace_id=<id>&model=<model>` recomputes a trace's cost
from its captured token usage under a different model's pricing — e.g. "this
call cost $0.0042 on sonnet; haiku would have been $0.0004".

## Production mode: the read-only dashboard <Badge type="tip" text="v0.7.0" />

Write your audit trail to a JSONL store, then serve the dashboard from it —
no running gateway, `metadata` mode, no content, no replay:

::: code-group

```python [Python]
from gavio.interceptors.audit import AuditInterceptor, JsonlSink

gw = (
    Gateway.builder()
    .provider("anthropic")
    .use(AuditInterceptor(sink=JsonlSink("audit.jsonl"), hash_chain=True))
    .build()
)
```

```bash [CLI]
gavio inspect --store audit.jsonl          # http://127.0.0.1:7411
gavio inspect --store audit.jsonl --port 7412 --token $TOKEN
```

:::

The store-backed server adds what production debugging needs: **RED stats**
(`/api/stats`), **search-by-content-hash** (`/api/traces?q=<hash prefix>` —
hash the prompt locally, look it up without content ever reaching the server),
and the **hash-chain verifier** (`/api/chain/verify`, `F-OBS-02` surfaced) that
walks every `previous_hash` link and reports the first tampered record.

For fleet-level observability, pair with the Prometheus metrics
(`F-OBS-08`) and the prebuilt
[Grafana dashboard](https://github.com/manojmallick/gavio/blob/main/docs/grafana/gavio-dashboard.json);
the InspectorEvent → OpenTelemetry span mapping is documented in
[docs/otel-mapping.md](https://github.com/manojmallick/gavio/blob/main/docs/otel-mapping.md).

## Recording decisions from your own interceptor

Anything you pass to `ctx.inspect(key, value)` during a hook surfaces as the
`decision` record on that hook's span — a harmless no-op when the inspector is
off. Interceptor state keyed by the interceptor's name (e.g. the cost router's
`cost_router` entry) surfaces automatically.

::: code-group

```python [Python]
async def before(self, request, ctx):
    ctx.inspect("triage", {"rule": "length-check", "routed": "standard"})
    return request
```

```typescript [TypeScript]
before(request, ctx) {
  ctx.inspect('triage', { rule: 'length-check', routed: 'standard' })
  return request
}
```

```java [Java]
public CompletableFuture<GavioRequest> before(GavioRequest request, InterceptorContext ctx) {
    ctx.inspect("triage", Map.of("rule", "length-check", "routed", "standard"));
    return CompletableFuture.completedFuture(request);
}
```

:::

## Configuration

| Option | Default | Description |
|---|---|---|
| `mode` | `full` in dev mode, else `metadata` | Capture level (see above) |
| `port` | `7411` | HTTP port; `0` picks an ephemeral port |
| `bind` | `127.0.0.1` | Non-loopback requires `auth_token` |
| `auth_token` | none | Bearer token for every endpoint |
| `max_traces` | `1000` | Ring-buffer capacity; oldest evicted |
| `unsafe_content_capture_ack` | `false` | Required for `full` outside dev mode |
| `start_server` | `true` | `false` = events/buffer only (used in tests) |

With the inspector disabled the request path is untouched — emission is a
no-op with no subscribers, and all suites run with it off by default.
