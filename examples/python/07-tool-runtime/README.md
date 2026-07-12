# 07 - Tool Runtime

Validates tool-result context before it re-enters model context: output schema,
freshness, conflicts, confidence, and provenance. **No API key, no network**.

```bash
pip install -r requirements.txt
python tool_runtime.py
```

The example analyzes conflicting tool results directly, then configures the
gateway to block stale/invalid tool context.

See also: [Tool Runtime guide](../../../docs/interceptors.md#tool-runtime-f-tool-01020304)
