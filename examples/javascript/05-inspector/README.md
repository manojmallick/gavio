# 05 · Inspector & multi-agent tracing (JavaScript)

Runs a small **orchestrator → researcher/writer** flow through a dev-mode
gateway with the **Inspector** enabled, in plain JavaScript (ESM) — **no API
key, no network** beyond the localhost UI.

```bash
npm install
node inspector.mjs
```

Then open **http://127.0.0.1:7411** while the script waits:

- **Traces** — a waterfall per request: every interceptor span + the provider call
- open a trace — the **PII diff** (original vs redacted, side by side)
- **DAG** — the agent call graph built from `agentId`/`parentTraceId`, with
  subtree cost rollups
- **Sessions** — traces grouped by `sessionId` with per-session totals

Press Enter in the terminal to exit.

See also: [Inspector guide](../../../docs/inspector.md)
