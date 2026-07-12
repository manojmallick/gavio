# Python SDK (`gavio`)

> PyPI package `gavio` · Python 3.10+ · async-first · zero mandatory dependencies

The Python SDK is the **reference implementation**. Source:
[`packages/gavio-py`](../../packages/gavio-py/).

- [Install](#install)
- [Gateway API](#gateway-api)
- [Interceptors](#interceptors)
- [Providers](#providers)
- [Runtime export](#runtime-export)
- [Testing](#testing)
- [Version support](#version-support)

---

## Install

```bash
pip install gavio            # core, zero deps
pip install "gavio[dev]"     # + pytest, pytest-asyncio, ruff, mypy
```

Optional extras: `gavio[redis]` (`RedisBackend`/`RedisVectorBackend` for the
semantic cache, F-CACHE-04). Planned for later versions: `gavio[presidio]`,
`gavio[otel]`, `gavio[elasticsearch]`, `gavio[all]`.

---

## Gateway API

Async-first. Build with the fluent builder; call `complete` (async) or
`complete_sync` (spins its own loop — for scripts / Django views).

```python
from gavio import Gateway, Provider
from gavio.interceptors.pii import PiiGuard

gw = (Gateway.builder()
      .provider(Provider.ANTHROPIC)   # or the string "anthropic"
      .model("claude-sonnet-4-6")
      .use(PiiGuard(sensitivity="strict"))
      .build())

resp = await gw.complete(
    messages=[{"role": "user", "content": "Hello"}],
    agent_id="my-agent",
    parent_trace_id=None,      # set for multi-agent DAG tracing
    session_id="sess-123",
    temperature=0.7, max_tokens=1000,   # → request.options
)

resp.content            # str, PII restored
resp.cost_usd           # float
resp.trace_id           # UUID v7 string
resp.usage.total_tokens
resp.interceptors_fired # list[str]
resp.audit              # AuditRecord
```

**Builder options:** `.provider()`, `.model()`, `.adapter(custom)`, `.use(...)`,
`.dev_mode(True)`, `.dry_run(True)`, `.pricing(PricingProvider)`,
`.exporter(JsonlRuntimeExporter(...))`.

- **dev mode** → `MockProvider` + stdout audit auto-wired; no network/key.
- **dry-run** → interceptors log but never modify or block.

---

## Interceptors

```python
from gavio.interceptors.pii import PiiGuard, Sensitivity, PiiMode
from gavio.interceptors.pii.scanners import (
    EmailScanner, IbanScanner, BsnScanner, CreditCardScanner,
    PhoneScanner, IpAddressScanner, SsnScanner, SecretScanner,
)
from gavio.interceptors.audit import AuditInterceptor, StdoutSink
from gavio.interceptors.reliability import RetryInterceptor, FallbackChain, TimeoutPolicy
from gavio.interceptors.tool_runtime import ToolRuntimeInterceptor
```

Typical production stack (order matters — audit outermost, PII before it):

```python
gw = (Gateway.builder()
      .provider("anthropic").model("claude-sonnet-4-6")
      .use(AuditInterceptor(sink=StdoutSink(pretty=True)))
      .use(PiiGuard(sensitivity=Sensitivity.STRICT, mode=PiiMode.REDACT))
      .use(TimeoutPolicy(timeout_seconds=30))
      .use(RetryInterceptor(max_attempts=3, base_delay_ms=500, jitter=True))
      .build())
```

See [interceptors.md](../interceptors.md) for options and custom scanners.

### Tool Runtime (v0.14.0)

`ToolRuntimeInterceptor` validates tool metadata from `metadata["tools"]` before
tool outputs re-enter model context. It supports declared input/output schemas,
freshness/TTL checks, conflict detection across configured result keys,
confidence scoring, and provenance records under `ctx.tools["runtime"]`.

```python
from gavio.interceptors.tool_runtime import ToolRuntimeInterceptor

gw = (Gateway.builder()
      .dev_mode(True)
      .use(ToolRuntimeInterceptor(on_failure="error"))
      .build())

await gw.complete(
    messages=[{"role": "user", "content": "summarize inventory"}],
    metadata={"tools": {"calls": [{
        "id": "inventory-1",
        "name": "inventory",
        "source": "warehouse",
        "created_at": "2026-07-12T12:00:00Z",
        "ttl_seconds": 60,
        "result": {"sku": "SKU-1", "quantity": 4},
        "output_schema": {"required": ["sku", "quantity"]},
    }]}},
)
```

### Policy packs (v0.12.0)

Policy packs expose scanner composition plus manifest metadata. Existing
scanner factories still work, but the built-in core and FinTech packs are now
first-class:

```python
from gavio.interceptors.pii import (
    PiiGuard,
    RegexPolicyRule,
    core_policy_pack,
    custom_policy_pack,
    fintech_policy_pack,
    policy_pack_scanners,
)

fintech = fintech_policy_pack()
print(fintech.manifest()["detectors"])

custom = custom_policy_pack(
    id="acme.internal",
    name="Acme Internal IDs",
    rules=[RegexPolicyRule("employee_id", "EMPLOYEE_ID", r"\bEMP-[0-9]{6}\b")],
)

PiiGuard(scanners=policy_pack_scanners(core_policy_pack(), fintech, custom))
```

---

## Providers

| Provider | `provider=` | Env var | Model |
|---|---|---|---|
| Anthropic | `"anthropic"` / `Provider.ANTHROPIC` | `ANTHROPIC_API_KEY` | `claude-sonnet-4-6` (default) |
| OpenAI | `"openai"` / `Provider.OPENAI` | `OPENAI_API_KEY` | `gpt-4o` (default) |
| Gemini | `"gemini"` / `Provider.GEMINI` | `GEMINI_API_KEY` | e.g. `gemini-2.0-flash` |
| Azure OpenAI | `"azure_openai"` / `Provider.AZURE_OPENAI` | `AZURE_OPENAI_API_KEY` + `AZURE_OPENAI_ENDPOINT` | your deployment name |
| OpenRouter | `"openrouter"` / `Provider.OPENROUTER` | `OPENROUTER_API_KEY` | `openai/gpt-4o` (default) |
| Ollama | `"ollama"` / `Provider.OLLAMA` | — (local; `OLLAMA_HOST`) | any pulled model, e.g. `llama3.2` — cost $0 |
| Mock | `"mock"` / dev mode | — | `mock` |

Gemini, Azure OpenAI, and Ollama were added in **v0.2.0**; OpenRouter was added
in **v0.13.0**. Every adapter uses stdlib HTTP (no vendor SDK).

Full adapter config:

```python
from gavio.providers.anthropic import AnthropicAdapter

gw = (Gateway.builder()
      .adapter(AnthropicAdapter(api_key="sk-ant-…", timeout_seconds=30))
      .build())
```

OpenRouter accepts direct adapter options for custom base URLs and optional
attribution headers:

```python
from gavio.providers.openrouter import OpenRouterAdapter

gw = (Gateway.builder()
      .adapter(OpenRouterAdapter(
          api_key="sk-or-...",
          http_referer="https://app.example",
          app_title="Gavio"))
      .model("openai/gpt-4o")
      .build())
```

---

## Inspector

Enable the embedded pipeline visualizer (`F-DX-09/10`, off by default) and open
`http://127.0.0.1:7411` — live traces, waterfalls, PII diffs, agent call
graphs, replay, stats. Full guide: [docs/inspector.md](../inspector.md).

```python
gw = Gateway.builder().dev_mode(True).inspect(True).build()
```

For production, write audits to a JSONL store and serve the read-only
dashboard from it (`F-DX-08`):

```python
from gavio.interceptors.audit import AuditInterceptor, JsonlSink
builder.use(AuditInterceptor(sink=JsonlSink("audit.jsonl"), hash_chain=True))
```

```bash
gavio inspect --store audit.jsonl
```

Cost Intelligence (v0.11.0) reads scalar labels from request metadata:

```python
await gw.complete(
    messages=[{"role": "user", "content": "price this"}],
    metadata={"costDimensions": {"tenant": "acme", "feature": "claims", "endpoint": "/chat"}},
)
```

Those labels can be used with `/api/stats?group_by=tenant` and
`/api/cost-report?group_by=feature`.

Cost Governance v2 (v1.2.0) adds policy/decision contracts and budget-aware
reports:

```python
from gavio.interceptors.governance import BudgetPolicy, BudgetPolicyControl

policy = BudgetPolicy(
    id="tenant-monthly",
    scope_type="tenant",
    scope_value="acme",
    window="monthly",
    limit_usd=500,
    hard_limit_action="fallback",
    fallback_model="gpt-4o-mini",
)

gw = Gateway.builder().use(BudgetPolicyControl(policy, estimated_request_cost_usd=0.02))
```

```bash
gavio cost report --audit audit.jsonl --group-by tenant --budget-policy budgets.json --pretty
```

## Runtime export

Runtime export (v1.1.0, `F-EXP-01`) writes the Inspector event envelope as
metadata-safe JSONL. Adding an exporter enables metadata-mode events without
starting the Inspector HTTP server.

```python
from gavio import Gateway, JsonlRuntimeExporter

gw = (
    Gateway.builder()
    .dev_mode(True)
    .exporter(JsonlRuntimeExporter("runtime-events.jsonl"))
    .build()
)
```

The JSONL exporter strips `messages`, `content`, and `diff` by default, even if
the local Inspector runs in `full` mode. See [runtime events](../runtime-events.md)
and [integrations](../integrations.md).

## Embeddings

`gw.embed(texts)` (`F-SEC-10`, since v0.9.0) runs embedding inputs through the
same interceptor pipeline as completions — PII is scanned and redacted before
the provider's embedding API is called; the response carries one vector per
input in `resp.embeddings`.

---

## Testing

`GavioTestKit` drives an interceptor chain in isolation against a `MockProvider`.

```python
from gavio.testing import GavioTestKit, MockProvider
from gavio.interceptors.pii import PiiGuard

async def test_pii_redacted():
    kit = GavioTestKit(
        interceptors=[PiiGuard()],
        provider=MockProvider(response="done [EMAIL_1]"),
    )
    result = await kit.run(messages=[{"role": "user", "content": "to jan@example.com"}])
    assert kit.pii_detected("EMAIL")
    assert "jan@example.com" not in kit.redacted_request.messages[0]["content"]
    assert result.content == "done jan@example.com"   # restored
```

Run the suite:

```bash
cd packages/gavio-py
pip install -e ".[dev]"
pytest tests/unit -q          # incl. the shared cross-SDK vectors
ruff check gavio tests
```

---

## Version support

- Python **3.10, 3.11, 3.12** (CI matrix). 3.9 not supported.
- Full PEP 484 type hints, `py.typed` marker, mypy-strict compatible.
- `Gateway` is thread/task-safe; `ScanContext` / `InterceptorContext` are
  per-request and never shared.
