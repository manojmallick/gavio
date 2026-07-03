# Inspector

**Added in v0.6.0** · Feature IDs: `F-DX-09` (core) · `F-DX-10` (UI)

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
