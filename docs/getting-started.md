# Getting started

Get a working LLM call through Gavio in under 5 minutes — in whichever language
you use. Every example below runs **as-is** in dev mode (no API key, no network).

- [Install](#install)
- [Hello, gateway (dev mode)](#hello-gateway-dev-mode)
- [Using a real provider](#using-a-real-provider)
- [What happens on each call](#what-happens-on-each-call)
- [Next steps](#next-steps)

---

## Install

| Language | Command |
|---|---|
| Python 3.10+ | `pip install gavio` |
| JavaScript (Node 18+) | `npm install gavio` |
| Java 17+ | add `io.github.manojmallick:gavio-core:0.4.0` (Maven) |

---

## Hello, gateway (dev mode)

Dev mode wires a **mock provider** + **stdout audit** automatically — the whole
pipeline runs in-process.

**Python**
```python
import asyncio
from gavio import Gateway
from gavio.interceptors.pii import PiiGuard

gw = Gateway.builder().dev_mode(True).use(PiiGuard()).build()

async def main():
    r = await gw.complete(
        messages=[{"role": "user", "content": "Email jan@example.com re NL91ABNA0417164300"}],
        agent_id="demo",
    )
    print(r.content)                    # PII restored in the reply
    print("pii:", r.audit.pii_entity_types, "cost:", r.cost_usd)

asyncio.run(main())
```

**JavaScript / TypeScript**
```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const gw = new Gateway({ devMode: true }).use(piiGuard())

const r = await gw.complete({
  messages: [{ role: 'user', content: 'Email jan@example.com re NL91ABNA0417164300' }],
  agentId: 'demo',
})
console.log(r.content)                  // PII restored in the reply
console.log('pii:', r.audit.piiEntityTypes, 'cost:', r.costUsd)
```

**Java**
```java
import io.gavio.Gateway;
import io.gavio.GavioRequest;
import io.gavio.interceptors.pii.PiiGuard;

Gateway gw = Gateway.builder().devMode(true).use(new PiiGuard()).build();

var r = gw.complete(GavioRequest.builder()
        .message("user", "Email jan@example.com re NL91ABNA0417164300")
        .agentId("demo")
        .build()).join();
System.out.println(r.content());        // PII restored
System.out.println(r.audit().piiEntityTypes());
```

All three detect `EMAIL` + `IBAN`, redact them **before** the provider call, and
restore the originals in the response text.

---

## Using a real provider

Set the provider + model and export the matching API key. PII is still scanned
before anything leaves your process.

**Python**
```python
from gavio import Gateway, Provider
from gavio.interceptors.pii import PiiGuard
from gavio.interceptors.audit import AuditInterceptor
from gavio.interceptors.reliability import RetryInterceptor, TimeoutPolicy

gw = (Gateway.builder()
      .provider(Provider.ANTHROPIC)          # reads ANTHROPIC_API_KEY
      .model("claude-sonnet-4-6")
      .use(PiiGuard(sensitivity="strict"))
      .use(AuditInterceptor(sink="stdout://"))
      .use(TimeoutPolicy(timeout_seconds=30))
      .use(RetryInterceptor(max_attempts=3))
      .build())
```

**JavaScript**
```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'
import { auditInterceptor } from 'gavio/interceptors/audit'
import { retryInterceptor, timeoutPolicy } from 'gavio/interceptors/reliability'

const gw = new Gateway({ provider: 'anthropic', model: 'claude-sonnet-4-6' })
  .use(piiGuard({ sensitivity: 'strict' }))
  .use(auditInterceptor({ sink: 'stdout' }))
  .use(timeoutPolicy({ timeoutMs: 30_000 }))
  .use(retryInterceptor({ maxAttempts: 3 }))
```

**Java**
```java
Gateway gw = Gateway.builder()
    .provider(Provider.ANTHROPIC)              // reads ANTHROPIC_API_KEY
    .model("claude-sonnet-4-6")
    .use(PiiGuard.builder().sensitivity(Sensitivity.STRICT).build())
    .use(AuditInterceptor.builder().sink(new StdoutSink()).build())
    .use(TimeoutPolicy.builder().timeoutSeconds(30).build())
    .use(RetryInterceptor.builder().maxAttempts(3).build())
    .build();
```

`openai`, `gemini`, `azure_openai`, and `ollama` (local, no key) work the same
way — the provider list grew in **v0.2.0**. See the per-language provider tables.

---

## What happens on each call

1. A `trace_id` (time-sortable UUID v7) is assigned.
2. **before** hooks run in registration order — PII Guard redacts, etc.
3. Reliability policies (retry / timeout / fallback) wrap the provider call.
4. The provider adapter runs.
5. **after** hooks run in reverse order — PII restore, then audit.
6. You get a `GavioResponse` with `content`, `cost_usd`/`costUsd`, `usage`,
   `interceptors_fired`, and an `audit` record.

See [architecture.md](./architecture.md) for the full lifecycle.

---

## Next steps

- [Architecture](./architecture.md) — the interceptor chain and data model
- [Interceptors](./interceptors.md) — every built-in + writing your own
- Per-language deep guides: [Python](./packages/python.md) · [JavaScript](./packages/javascript.md) · [Java](./packages/java.md)
