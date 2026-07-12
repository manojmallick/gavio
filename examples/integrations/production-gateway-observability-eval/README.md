# Production Gateway Observability Eval

Offline production-style ecosystem trust app for v2.7.0. It combines a Gavio
gateway call with LiteLLM labels, metadata-safe JSONL runtime export,
OpenLIT-style spans, Langfuse/promptfoo adapter payloads, an eval gate, audit
hash-chain evidence, and a Production Trust Package.

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/production-gateway-observability-eval/app.py
```

No provider key, external gateway, observability backend, or eval service is
required.
