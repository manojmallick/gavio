<div align="center">

# 🌉 Gavio

**The open standard AI gateway for production systems.**

PII protection · audit trails · reliability · cost control — as composable
interceptors. **Same API in Python, Java, and JavaScript.**

[![CI](https://github.com/manojmallick/gavio/actions/workflows/ci.yml/badge.svg)](https://github.com/manojmallick/gavio/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/gavio?color=3776ab&label=pypi&logo=python&logoColor=white)](https://pypi.org/project/gavio/)
[![npm](https://img.shields.io/npm/v/gavio?color=cb3837&label=npm&logo=npm)](https://www.npmjs.com/package/gavio)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.manojmallick/gavio-core?color=f89820&label=maven%20central&logo=apachemaven&logoColor=white)](https://central.sonatype.com/artifact/io.github.manojmallick/gavio-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-22c55e.svg)](LICENSE)
[![Zero deps](https://img.shields.io/badge/core%20dependencies-zero-22c55e)](#design-principles)
[![Docs](https://img.shields.io/badge/docs-live-7c6af7)](https://manojmallick.github.io/gavio/)

📖 **Docs:** [manojmallick.github.io/gavio](https://manojmallick.github.io/gavio/)

</div>

---

## What is Gavio?

Gavio sits **between your application and any LLM provider**. Every request
passes through a pre/post **interceptor chain** — PII redaction, retries, cost
tracking, audit logging — before and after the provider call:

```
Request → [ PII Guard · Secret Scanner · … ] → Provider → [ … · PII Restore · Audit ] → Response
```

Every team re-implements the same production concerns around LLM calls: redact
PII before it leaves the building, retry on 429s, fall back to a second
provider, log an audit trail, track spend. Gavio ships them once, as swappable
interceptors, with **identical behaviour across three languages** — enforced by
[shared test vectors](./test-vectors/).

- **Provider-agnostic** — OpenAI, Anthropic, Gemini, Azure, Ollama, Mock. Switching is a config change.
- **Zero mandatory dependencies** in every core (stdlib HTTP everywhere — no vendor SDKs).
- **Dev mode** — the whole stack runs in-process with a mock provider. No API key, no network.
- **Audit by default** — every call logged as metadata + SHA-256 content hashes (never raw text).

> **Status:** v0.2.0 (Production core). Pre-1.0, APIs may still change. See the [CHANGELOG](./CHANGELOG.md).

---

## Quick start (dev mode — no API key, no network)

<table>
<tr><th>Python</th><th>JavaScript / TypeScript</th><th>Java</th></tr>
<tr>
<td>

```python
from gavio import Gateway
from gavio.interceptors.pii import PiiGuard

gw = (Gateway.builder()
      .dev_mode(True)
      .use(PiiGuard())
      .build())

r = await gw.complete(messages=[
  {"role": "user",
   "content": "mail jan@example.com"}])
print(r.content)        # PII restored
print(r.audit.pii_entity_types)
```

</td>
<td>

```typescript
import { Gateway } from 'gavio'
import { piiGuard } from 'gavio/interceptors/pii'

const gw = new Gateway({ devMode: true })
  .use(piiGuard())

const r = await gw.complete({ messages: [
  { role: 'user',
    content: 'mail jan@example.com' }] })
console.log(r.content)   // PII restored
console.log(r.audit.piiEntityTypes)
```

</td>
<td>

```java
Gateway gw = Gateway.builder()
    .devMode(true)
    .use(new PiiGuard())
    .build();

var r = gw.complete(GavioRequest.builder()
    .message("user", "mail jan@example.com")
    .build()).join();
System.out.println(r.content());
System.out.println(r.audit().piiEntityTypes());
```

</td>
</tr>
</table>

All three print the reply with the email **restored**, and an audit record
showing `EMAIL` was detected and redacted before the (mock) provider ever saw it.

---

## Install

| Language | Command | Docs |
|---|---|---|
| **Python** 3.10+ | `pip install gavio` | [packages/gavio-py](./packages/gavio-py/README.md) · [docs/packages/python.md](./docs/packages/python.md) |
| **JavaScript** (Node 18+) | `npm install gavio` | [packages/gavio-js](./packages/gavio-js/README.md) · [docs/packages/javascript.md](./docs/packages/javascript.md) |
| **Java** 17+ (Maven) | `io.github.manojmallick:gavio-core:0.2.0` | [packages/gavio-java](./packages/gavio-java/README.md) · [docs/packages/java.md](./docs/packages/java.md) |

---

## Packages

Gavio is a monorepo. Each SDK is independently versioned-in-lockstep and
published to its native registry.

### 🐍 Python — `gavio` (PyPI)

The **reference implementation**. Async-first (`await gw.complete(...)`), sync
wrapper (`complete_sync`), full type hints + `py.typed`. Zero mandatory deps;
optional extras (`gavio[presidio]`, `gavio[redis]`, …) land in later versions.

```bash
pip install gavio
```

→ **[Full Python guide](./docs/packages/python.md)** · [package README](./packages/gavio-py/README.md)

### 🟨 JavaScript / TypeScript — `gavio` (npm)

Written in TypeScript, ships full type definitions, **dual ESM + CJS build**
with per-subpath `exports` for tree-shaking. Native `fetch`, `node:crypto`.
Node 18+, Deno, Bun.

```bash
npm install gavio
```

→ **[Full JavaScript guide](./docs/packages/javascript.md)** · [package README](./packages/gavio-js/README.md)

### ☕ Java — `io.github.manojmallick:gavio-*` (Maven Central)

Multi-artifact Maven project (`gavio-core`, `gavio-interceptor-pii`,
`gavio-interceptor-audit`, `gavio-interceptor-reliability`,
`gavio-provider-openai`, `gavio-provider-anthropic`, `gavio-testing`).
Immutable records + builders, `CompletableFuture` async, Java 17+.

```xml
<dependency>
  <groupId>io.github.manojmallick</groupId>
  <artifactId>gavio-core</artifactId>
  <version>0.2.0</version>
</dependency>
```

→ **[Full Java guide](./docs/packages/java.md)** · [package README](./packages/gavio-java/README.md)

---

## What ships

**v0.1.0 — Foundation**

| Area | Feature | ID |
|---|---|---|
| **Privacy** | PII Guard (Email, IBAN·mod-97, BSN·11-proef, CreditCard·Luhn, Phone, IP, SSN) | `F-SEC-01` |
| | Secret scanner (API keys, AWS, GitHub, JWT, PEM, DB URLs) | `F-SEC-04` |
| **Reliability** | Retry (exp backoff + jitter), Fallback chain, Timeout | `F-REL-01/02/07` |
| **Cost** | Per-request `cost_usd` tracking | `F-GOV-01` |
| **Observability** | Audit interceptor + `AuditRecord` (SHA-256 hashes), stdout sink | `F-OBS-01/05` |
| **DX** | Dev mode, dry-run mode, test kit | `F-DX-01/02` |
| **Providers** | OpenAI, Anthropic (stdlib HTTP), Mock | — |

**v0.2.0 — Production core**

| Area | Feature | ID |
|---|---|---|
| **Caching** | Semantic + exact cache (cosine + SHA-256), in-memory backends | `F-CACHE-01/02/03` |
| **Reliability** | Circuit breaker, load balancer | `F-REL-03/04` |
| **Governance** | Budget caps, rate limiting, model RBAC | `F-GOV-02/03/04` |
| **Quality** | Guardrails — JSON-schema + regex allow/deny | `F-QUA-01/02` |
| **Security** | Prompt-injection defense | `F-SEC-05` |
| **Observability** | Hash-chain (tamper-evident) audit, multi-agent DAG trace | `F-OBS-02/03` |
| **DX** | OpenAI drop-in shim, config loader | `F-DX-04/05` |
| **Providers** | Gemini, Azure OpenAI, Ollama | — |

**325 tests** across the three SDKs (Python 102 · JavaScript 115 · Java 108).
See the [CHANGELOG](./CHANGELOG.md) and [interceptors guide](./docs/interceptors.md).

---

## Design principles

- **P1 · Interface-first** — every feature is a public interface you can swap or extend.
- **P2 · Interceptor chain** — pre/post hooks, explicit composition, no hidden magic.
- **P3 · Provider-agnostic** — no provider-specific code leaks into your app.
- **P4 · Zero infra in dev** — `dev_mode` runs everything in-process.
- **P5 · Audit by default** — opt-out, not opt-in.
- **P7 · Dry-run first** — log what *would* happen without blocking.
- **P8 · Typed everywhere** — TS generics, Python hints, Java generics.

---

## Documentation

| Doc | What |
|---|---|
| [docs/getting-started.md](./docs/getting-started.md) | 5-minute quickstart, all three languages |
| [docs/architecture.md](./docs/architecture.md) | Request lifecycle, the interceptor chain, data model |
| [docs/interceptors.md](./docs/interceptors.md) | Every built-in interceptor + writing your own |
| [docs/packages/](./docs/packages/) | Deep guide per SDK |
| [examples/](./examples/) | Runnable example projects (Python) |
| [spec/](./spec/) | Canonical JSON Schema data model |
| [test-vectors/](./test-vectors/) | Shared cross-SDK conformance cases |
| [RELEASING.md](./RELEASING.md) | How releases are cut (PyPI + Maven Central + npm) |
| [CONTRIBUTING.md](./CONTRIBUTING.md) | Contribution guide + PR requirements |

---

## Repository layout

```
gavio/
├── spec/                     canonical data model (JSON Schema)
├── test-vectors/             shared cases every SDK must pass
├── packages/
│   ├── gavio-py/             Python SDK  (PyPI: gavio)
│   ├── gavio-js/             JS/TS SDK   (npm: gavio)
│   └── gavio-java/           Java SDK    (Maven: io.github.manojmallick:gavio-*)
├── docs/                     documentation
└── .github/workflows/        ci.yml (test all 3) · release.yml (publish all 3)
```

---

## License

[MIT](./LICENSE) © 2026 Manoj Mallick

---

<div align="center">

MIT © 2026 Manoj Mallick · Made in Amsterdam 🇳🇱

</div>
