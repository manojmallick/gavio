# Prompt Registry v2

File-backed prompt registry workflow with semantic versions, approval metadata,
metadata-safe diffs, and signed manifest verification.

Run from the repository checkout:

```bash
PYTHONPATH=../../../packages/gavio-py python3 prompt_registry_v2.py
```

The example loads `prompts.json`, verifies its HMAC-SHA256 manifest signature,
selects the latest compatible `support.reply` prompt with `^1.0.0`, renders a
message, and prints a diff from `1.0.0` to `1.1.0`. Diff entries hash prompt
message content instead of exposing raw prompt text.
