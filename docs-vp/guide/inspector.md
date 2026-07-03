# Inspector

**Added in v0.6.0** · Feature IDs: `F-DX-09` (core) · `F-DX-10` (UI)

The Gavio Inspector is an embedded, zero-dependency dev-time visualizer. While
a request moves through the interceptor chain, the gateway emits span events —
which interceptor fired, in what order, what each one changed, how long each
took, what the provider returned. A bounded ring buffer assembles them into
traces, and a localhost HTTP server renders them in a self-contained web UI.

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
| `GET /api/traces?limit=N` | trace summaries, chronological |
| `GET /api/traces/{id}` | one assembled trace: summary + ordered events |
| `GET /api/stream` | live event feed (Server-Sent Events) |
| `GET /` | the bundled UI |

The event contract is canonical JSON Schema:
[`spec/InspectorEvent.schema.json`](https://github.com/manojmallick/gavio/blob/main/spec/InspectorEvent.schema.json).
Cross-SDK parity is enforced by shared
[event-sequence test vectors](https://github.com/manojmallick/gavio/tree/main/test-vectors/inspector)
that all three suites run.

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
