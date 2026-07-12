# Ecosystem integration examples

These examples run offline and show how to label Gavio requests when the app
also uses common AI stack tools.

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
| Full stack | [`full-stack/integration_stack.py`](./full-stack/integration_stack.py) |

From the repository:

```bash
PYTHONPATH=packages/gavio-py python examples/integrations/litellm/recipe.py
PYTHONPATH=packages/gavio-py python examples/integrations/full-stack/integration_stack.py
```

After installing the 1.9 package, the same scripts run without `PYTHONPATH`.
