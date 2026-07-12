# Gavio — Python SDK

> AI request runtime and inspector for production systems. PII protection,
> audit trails, reliability, cost intelligence, policy packs, and provider
> adapters as composable interceptors.

`gavio` sits between your application and any LLM provider. The same request
passes through a pre/post interceptor chain — PII redaction, retries, caching,
budgets, audit logging, runtime context — before and after the provider call. Same API in
[Python, Java, and JavaScript](https://github.com/manojmallick/gavio), enforced
by shared cross-SDK test vectors.

Part of the [Gavio](https://manojmallick.github.io/gavio) project. MIT licensed.

## Install

```bash
pip install gavio            # zero mandatory dependencies
pip install gavio[redis]     # + distributed cache backend
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

OpenAI, Gemini, Azure OpenAI, OpenRouter, and Ollama adapters work the same way
(`Provider.OPENAI`, `Provider.GEMINI`, `Provider.AZURE_OPENAI`,
`Provider.OPENROUTER`, `Provider.OLLAMA`) — switching providers is a config
change, never an application change.

Streaming buffers the provider stream so post-interceptors (guardrails, PII
restore, audit) run on the complete response before any chunk reaches you:

```python
async for chunk in gw.stream(messages=[{"role": "user", "content": "Hi"}]):
    print(chunk, end="")
```

Embeddings run through the same pipeline — inputs are PII-scanned before the
provider's embedding API is called:

```python
resp = await gw.embed(["index this: contact jan@example.com"])
print(len(resp.embeddings))         # one vector per input, PII never left
```

## The Inspector

An embedded, zero-dependency visualizer for the pipeline: live traces,
per-interceptor waterfalls, PII redaction diffs, multi-agent call graphs,
replay, RED stats, and a read-only production dashboard.

```python
gw = Gateway.builder().dev_mode(True).inspect(True).build()
# open http://127.0.0.1:7411 and send a request
```

In production, write audits to a JSONL store and serve the dashboard from it:

```python
from gavio.interceptors.audit import AuditInterceptor, JsonlSink
gw = Gateway.builder().provider(Provider.ANTHROPIC) \
    .use(AuditInterceptor(sink=JsonlSink("audit.jsonl"), hash_chain=True)).build()
```

```bash
gavio inspect --store audit.jsonl   # metadata mode: no content, no replay
```

## What's inside

Every feature is an interceptor you compose explicitly — no hidden magic.

- **Privacy & security** — PII Guard with Email, IBAN (mod-97), BSN (11-proef),
  CreditCard (Luhn), Phone, IP, SSN scanners and redact/mask/tag/block +
  restore (`F-SEC-01`); secret/credential scanner (`F-SEC-04`); prompt
  injection guard (`F-SEC-05`); embedding call guard (`F-SEC-10`); Policy Pack
  manifests for core, FinTech, and custom regex-rule packs (`F-PACK-01/02/05`).
- **Reliability** — retry with backoff (`F-REL-01`), provider fallback chain
  (`F-REL-02`), circuit breaker (`F-REL-03`), load balancing (`F-REL-04`),
  buffered streaming (`F-REL-06`), timeouts (`F-REL-07`).
- **Caching** — SHA-256 exact + semantic (cosine) cache with in-memory and
  Redis backends (`F-CACHE-01/02/03/04`).
- **Cost & governance** — per-request cost tracking (`F-GOV-01`), budget caps
  (`F-GOV-02`), rate limiting (`F-GOV-03`), per-role model policy (`F-GOV-04`),
  cost-optimiser routing (`F-GOV-06`).
- **Observability** — audit-by-default with SHA-256 content hashes, never raw
  text (`F-OBS-01`), tamper-evident hash chain (`F-OBS-02`), multi-agent DAG
  tracing via `agent_id`/`parent_trace_id` (`F-OBS-03`), prompt lineage
  (`F-OBS-04`), Prometheus metrics (`F-OBS-08`), stdout + JSONL sinks.
- **Quality** — guardrails with JSON-schema and regex validators
  (`F-QUA-01/02`), composite risk scoring (`F-QUA-06`).
- **Inspector** — dev-time visualizer (`F-DX-09/10`), agent call graphs and
  session views (`F-OBS-10`), trace replay (`F-DX-11`), PII-sanitized
  test-case export (`F-DX-12`), read-only production dashboard + `gavio
  inspect` CLI (`F-DX-08`).
- **Developer experience** — dev mode (`F-DX-01`), dry-run (`F-DX-02`),
  `GavioTestKit` (`F-DX-03`), OpenAI drop-in shim (`F-DX-04`), config-file
  gateway construction (`F-DX-05`).
- **Providers** — OpenAI, Anthropic, Gemini, Azure OpenAI, OpenRouter, Ollama,
  Mock.

See the [documentation site](https://manojmallick.github.io/gavio), the
[Python guide](../../docs/packages/python.md), the runnable
[examples](../../examples/), and the [CHANGELOG](../../CHANGELOG.md) for
version-by-version detail.

## Tests

```bash
pip install -e ".[dev]"
pytest tests/unit -v
ruff check gavio
```
