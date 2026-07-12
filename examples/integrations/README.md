# Ecosystem integration examples

These examples run offline and show how to label Gavio requests, build
metadata-only adapter payloads, and smoke-test ecosystem trust evidence when
the app also uses common AI stack tools.

| Integration | Example |
|---|---|
| LiteLLM | [`litellm/recipe.py`](./litellm/recipe.py) |
| Portkey | [`portkey/recipe.py`](./portkey/recipe.py) |
| Helicone | [`helicone/recipe.py`](./helicone/recipe.py) |
| Langfuse | [`langfuse/recipe.py`](./langfuse/recipe.py) |
| OpenLIT | [`openlit/recipe.py`](./openlit/recipe.py) |
| promptfoo | [`promptfoo/recipe.py`](./promptfoo/recipe.py) |
| LangChain | [`langchain/recipe.py`](./langchain/recipe.py) |
| LangGraph | [`langgraph/recipe.py`](./langgraph/recipe.py) |
| Vercel AI SDK | [`vercel-ai-sdk/recipe.py`](./vercel-ai-sdk/recipe.py) |
| OpenAI SDK | [`openai-sdk/recipe.py`](./openai-sdk/recipe.py) |
| Adapter payload tour | [`adapters/recipe.py`](./adapters/recipe.py) |
| Full stack | [`full-stack/integration_stack.py`](./full-stack/integration_stack.py) |
| Production gateway/observability/eval trust app | [`production-gateway-observability-eval/app.py`](./production-gateway-observability-eval/app.py) |
| Production agent/framework handoff trust app | [`production-agent-framework/app.py`](./production-agent-framework/app.py) |

From the repository:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/litellm/recipe.py
PYTHONPATH=packages/gavio-py python examples/integrations/adapters/recipe.py
PYTHONPATH=packages/gavio-py python examples/integrations/full-stack/integration_stack.py
PYTHONPATH=packages/gavio-py python examples/integrations/production-gateway-observability-eval/app.py
PYTHONPATH=packages/gavio-py python examples/integrations/production-agent-framework/app.py
```

The v2.7.0 compatibility matrix is generated from
[`test-vectors/integrations/ecosystem-trust.json`](../../test-vectors/integrations/ecosystem-trust.json)
into [`docs/integrations/compatibility-matrix.json`](../../docs/integrations/compatibility-matrix.json):

```bash
node scripts/gen-ecosystem-trust-matrix.mjs --check
```

After installing the current package line, the same scripts run without
`PYTHONPATH`.
