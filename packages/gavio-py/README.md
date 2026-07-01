# Gavio — Python SDK

> The open standard AI gateway for production systems. PII protection, audit
> trails, reliability, and cost control as composable interceptors.

`gavio` sits between your application and any LLM provider. The same request
passes through a pre/post interceptor chain — PII redaction, retries, cost
tracking, audit logging — before and after the provider call.

Part of the [Gavio](https://gavio.io) project. MIT licensed.

## Install

```bash
pip install gavio            # zero mandatory dependencies
pip install gavio[dev]       # + pytest, ruff, mypy
```

Requires Python 3.10+.

## Quick start (dev mode — no API key, no network)

```python
import asyncio
from gavio import Gateway
from gavio.interceptors.pii import PiiGuard

gw = (
    Gateway.builder()
    .dev_mode(True)                 # MockProvider + stdout audit
    .use(PiiGuard())                # redact PII before it leaves the process
    .build()
)

async def main():
    resp = await gw.complete(
        messages=[{"role": "user", "content": "Email jan@example.com about NL91ABNA0417164300"}],
        agent_id="demo",
    )
    print(resp.content)             # PII restored in the reply
    print(f"cost=${resp.cost_usd:.6f} latency={resp.latency_ms}ms")
    print("pii types:", resp.audit.pii_entity_types)

asyncio.run(main())
```

## Real providers

```python
from gavio import Gateway, Provider
from gavio.interceptors.pii import PiiGuard
from gavio.interceptors.audit import AuditInterceptor
from gavio.interceptors.reliability import RetryInterceptor, TimeoutPolicy

gw = (
    Gateway.builder()
    .provider(Provider.ANTHROPIC)          # reads ANTHROPIC_API_KEY
    .model("claude-sonnet-4-6")
    .use(PiiGuard(sensitivity="strict"))
    .use(AuditInterceptor(sink="stdout://"))
    .use(TimeoutPolicy(timeout_seconds=30))
    .use(RetryInterceptor(max_attempts=3))
    .build()
)

resp = await gw.complete(messages=[{"role": "user", "content": "Hi"}])
```

`OPENAI_API_KEY` / `Provider.OPENAI` work the same way.

## What ships in v0.1.0

- **Core** — `Gateway` fluent builder, `InterceptorChain`, `GavioRequest` /
  `GavioResponse`, UUID v7 `trace_id`, `agent_id` / `parent_trace_id`.
- **PII Guard (F-SEC-01)** — Email, IBAN (mod-97), BSN (11-proef),
  CreditCard (Luhn), Phone, IP, SSN scanners, redact/mask/tag/block, restore.
- **Secret Scanner (F-SEC-04)** — API keys, JWTs, PEM keys, DB URLs.
- **Reliability** — retry with backoff (F-REL-01), fallback chain (F-REL-02),
  timeout (F-REL-07).
- **Cost tracking (F-GOV-01)** — per-request `cost_usd`.
- **Audit (F-OBS-01)** — `AuditRecord` + `StdoutSink` (F-OBS-05).
- **Dev mode (F-DX-01)** and **dry-run mode (F-DX-02)**.
- **Providers** — OpenAI, Anthropic, Mock.

See the [Python guide](../../docs/packages/python.md) and [CHANGELOG.md](../../CHANGELOG.md).

## Tests

```bash
pip install -e ".[dev]"
pytest tests/unit -v
ruff check gavio
```
