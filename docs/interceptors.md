# Interceptors

Every Gavio feature is an interceptor. This page covers the built-ins shipped in
v0.1.0 and how to write your own.

- [PII Guard](#pii-guard-f-sec-01)
- [Secret scanner](#secret-scanner-f-sec-04)
- [Reliability: retry / timeout / fallback](#reliability)
- [Audit](#audit-f-obs-01)
- [Writing a custom scanner](#writing-a-custom-scanner)
- [Writing a custom interceptor](#writing-a-custom-interceptor)

---

## PII Guard (`F-SEC-01`)

Scans every request message before the provider call and acts on detected
entities. In `redact` mode the originals are restored in the response.

**Built-in tier-1 scanners** (all validated, not just regex):

| Scanner | Entity | Validation |
|---|---|---|
| Email | `EMAIL` | RFC-5322-ish |
| IBAN | `IBAN` | ISO 13616 mod-97 checksum |
| BSN | `BSN` | Dutch 11-proef |
| CreditCard | `CREDIT_CARD` | Luhn |
| Phone | `PHONE` | E.164 + national |
| IpAddress | `IP_ADDRESS` | IPv4/IPv6 parse |
| SSN | `SSN` | US format |

**Modes:** `redact` (typed placeholder `[EMAIL_1]`, restored on response) ·
`mask` (`****`) · `tag` (`<EMAIL>…</EMAIL>`) · `block` (raise & refuse).

**Sensitivity:** `strict` (all matches) · `balanced` (confidence ≥ 0.6) ·
`permissive` (≥ 0.9).

Overlapping matches are resolved greedily (prefer the longer / higher-confidence
match). Only entity **types and counts** are logged — never raw values.

```python
from gavio.interceptors.pii import PiiGuard, Sensitivity, PiiMode
from gavio.interceptors.pii.scanners import EmailScanner, IbanScanner

PiiGuard(
    scanners=[EmailScanner(), IbanScanner()],   # omit to use the default set
    sensitivity=Sensitivity.STRICT,
    mode=PiiMode.REDACT,
    restore_on_response=True,
)
```

---

## Secret scanner (`F-SEC-04`)

Part of the default scanner set. Detects OpenAI (`sk-…`) / Anthropic
(`sk-ant-…`) keys, AWS access keys (`AKIA…`), GitHub tokens, JWTs, PEM private
keys, and DB connection strings (`postgres://…`, etc.) — all as entity type
`SECRET`.

---

## Reliability

All three implement `ExecutorPolicy` and wrap the provider call
(first-registered = outermost — see [architecture](./architecture.md#executor-policies)).

| Policy | ID | Key options |
|---|---|---|
| `RetryInterceptor` | `F-REL-01` | `max_attempts`, `base_delay_ms`, `max_delay_ms`, `jitter`, `retry_on` |
| `FallbackChain` | `F-REL-02` | list of fallback adapters, tried in order on `ProviderError` |
| `TimeoutPolicy` | `F-REL-07` | `timeout_seconds` — raises `TimeoutError` on breach |

Retry backs off exponentially with full jitter and only retries transient errors
(`RateLimitError`, `TimeoutError`, `ServerError`, `ProviderUnavailableError`).

---

## Audit (`F-OBS-01`)

Register it **outermost** so its `after` runs last and sees the final response.
It builds an `AuditRecord` (metadata + SHA-256 hashes of the redacted prompt and
the response — never raw text) and writes it to a sink. v0.1.0 ships
`StdoutSink` (`F-OBS-05`); implement `AuditSink.write(record)` for your own
(Elasticsearch, Kafka, …).

```
[gavio:audit] trace=019f1ac0-b801-7f48… mock/mock tokens=16 cost=$0.000000 \
              latency=0ms cache=miss pii=EMAIL,IBAN interceptors=[audit,pii_guard]
```

---

## Writing a custom scanner

Implement the `PiiScanner` interface and add it to `PiiGuard`. Use
`ctx.next_index(type)` for stable placeholder numbering.

```python
import re
from gavio.interceptors.pii.scanner import PiiScanner
from gavio.interceptors.pii.match import PiiMatch

class IngAccountScanner(PiiScanner):
    entity_type = "ING_ACCOUNT"
    _PATTERN = re.compile(r"\bNL\d{2}INGB\d{10}\b")

    def scan(self, text, ctx):
        return [
            PiiMatch(
                entity_type=self.entity_type,
                start=m.start(), end=m.end(), value=m.group(),
                replacement=f"[ING_ACCOUNT_{ctx.next_index(self.entity_type)}]",
            )
            for m in self._PATTERN.finditer(text)
        ]
```

The JavaScript and Java equivalents use a factory object / class implementing
the same interface — see each [package guide](./packages/).

---

## Writing a custom interceptor

Implement `name` plus any of `before` / `after` / `on_error`:

```python
from gavio.interceptors.base import Interceptor

class HeaderTagger(Interceptor):
    name = "header-tagger"

    async def before(self, request, ctx):
        ctx.mark_fired(self.name)
        request.metadata["tagged_at_layer"] = "edge"
        return request
```

For something that must retry or wrap the provider call, subclass
`ExecutorPolicy` and implement `around(request, ctx, call_next)` instead.
