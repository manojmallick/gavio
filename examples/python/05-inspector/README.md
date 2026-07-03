# 05 · Inspector & multi-agent tracing

Runs a small **orchestrator → researcher/writer** flow through a dev-mode
gateway with the **Inspector** enabled — **no API key, no network** beyond the
localhost UI.

```bash
pip install -r requirements.txt
python inspector.py
```

Then open **http://127.0.0.1:7411** while the script waits:

- **Traces** — a waterfall per request: every interceptor span + the provider call
- open a trace — the **PII diff** (original vs redacted, side by side)
- **DAG** — the agent call graph built from `agent_id`/`parent_trace_id`, with
  subtree cost rollups
- **Sessions** — traces grouped by `session_id` with per-session totals

Press Enter in the terminal to exit.

See also: [Inspector guide](../../../docs/inspector.md)
