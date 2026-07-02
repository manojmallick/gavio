# Python SDK (`gavio`)

> PyPI package `gavio` · Python 3.10+ · async-first · zero mandatory dependencies

The Python SDK is the **reference implementation**. Source:
[`packages/gavio-py`](../../packages/gavio-py/).

- [Install](#install)
- [Gateway API](#gateway-api)
- [Interceptors](#interceptors)
- [Providers](#providers)
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
`.dev_mode(True)`, `.dry_run(True)`, `.pricing(PricingProvider)`.

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

---

## Providers

| Provider | `provider=` | Env var | Default model |
|---|---|---|---|
| Anthropic | `"anthropic"` / `Provider.ANTHROPIC` | `ANTHROPIC_API_KEY` | `claude-sonnet-4-6` |
| OpenAI | `"openai"` / `Provider.OPENAI` | `OPENAI_API_KEY` | `gpt-4o` |
| Mock | `"mock"` / dev mode | — | `mock` |

Full adapter config:

```python
from gavio.providers.anthropic import AnthropicAdapter

gw = (Gateway.builder()
      .adapter(AnthropicAdapter(api_key="sk-ant-…", timeout_seconds=30))
      .build())
```

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
