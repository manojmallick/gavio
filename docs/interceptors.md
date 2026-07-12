# Interceptors

Every Gavio feature is an interceptor. This page covers the built-ins and how to
write your own.

**v0.1.0** — [PII Guard](#pii-guard-f-sec-01) ·
[Secret scanner](#secret-scanner-f-sec-04) · [Reliability](#reliability) ·
[Audit](#audit-f-obs-01) · [custom scanner](#writing-a-custom-scanner) ·
[custom interceptor](#writing-a-custom-interceptor)

**v0.2.0 (Production core)** — [Caching](#caching-f-cache-01020304) ·
[Circuit breaker + load balancer](#reliability--circuit-breaker--load-balancer-f-rel-0304) ·
[Governance](#governance-f-gov-020304) · [Guardrails](#guardrails-f-qua-0102) ·
[Prompt-injection](#prompt-injection-defense-f-sec-05) ·
[Tamper-evident audit](#tamper-evident-audit--multi-agent-trace-f-obs-0203)

**v0.3.0 (Observability depth)** — [Prompt lineage](#prompt-lineage-f-obs-04) ·
[Prometheus metrics](#prometheus-metrics-f-obs-08) ·
[Risk scoring](#risk-scoring-f-qua-06) ·
[Streaming](#streaming--streambuffer-f-rel-06)

**v0.4.0** — [Redis cache backend](#redis-backend-f-cache-04)

**v0.5.0** — [Cost-optimiser routing](#cost-optimiser-routing-f-gov-06)

**v0.6.0 (Inspector)** — see the dedicated [Inspector page](./inspector.md)
(`F-DX-09`/`F-DX-10`): not an interceptor itself, but it instruments every
interceptor in the chain with live span events and a local web UI.

**v0.7.0 (Inspector: agentic & production mode)** — the [Inspector page](./inspector.md)
covers the additions (`F-OBS-10`/`F-DX-11`/`F-DX-08`/`F-DX-12`): agent call
graphs, session views, trace replay, RED stats, hash-chain verification,
test-case export, and the `gavio inspect --store` read-only dashboard.

**v0.9.0** — Embedding call guard (`F-SEC-10`): `gw.embed(texts)` runs
embedding inputs through the same pre-interceptor chain (PII guard included)
before the provider's embedding API.

**v0.12.0** — [Policy Pack architecture](#domain-policy-packs-f-pack-010205):
core/FinTech manifests, detector action metadata, redaction strategies, and
custom regex-rule packs.

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

### Image PII (`F-SEC-09`)

`ModalityGuard` extends the PII pipeline to **image inputs** passed on the
side-channel `images` field of a request. Each `ModalityScanner` extracts text
(OCR) and/or direct detections (e.g. faces); the extracted text is run through
the standard text PII scanners, and every detected entity type is recorded on
the context — so image detections appear in the `AuditRecord`'s
`pii_entity_types` exactly like text PII. Scanning happens in the `before` hook,
before any provider call.

```python
from gavio.interceptors.pii import ModalityGuard, OcrModalityScanner

# OcrModalityScanner needs the optional 'ocr' extra: pip install 'gavio[ocr]'
ModalityGuard(scanners=[OcrModalityScanner()], on_detect="tag")  # or "block"

await gateway.complete(messages=[...], images=[png_bytes])
```

`ModalityScanner` is the extension point — implement it for a custom OCR or
face-detection backend. `on_detect="block"` raises `PiiBlockedError` on any
detection; the default `"tag"` records detections without blocking.

### Domain policy packs (`F-PACK-01/02/05`)

Generic PII detection misses domain-specific identifiers. **Policy packs** now
wrap scanner sets in a manifest: pack id/name/version/domain, detectors,
default action (`allow`/`flag`/`redact`/`mask`/`hash`/`block`/`route`/
`require-approval`), redaction strategy (`tokenize`/`mask`/`hash`/`redact`),
audit labels, and scanner composition. The scanners still plug into `PiiGuard`
unchanged.

Built-ins:

- **Core PII** (`gavio.core-pii`) backs the default scanner set.
- **FinTech** (`gavio.fintech`) promotes the v0.10.0 scanner prototype into a
  first-class pack beyond the core `IBAN` scanner:

| Scanner | Entity | Validation |
|---|---|---|
| SWIFT/BIC | `SWIFT_BIC` | Context-gated — requires a `SWIFT`/`BIC` label (no false positives on ordinary words) |
| Routing number | `ROUTING_NUMBER` | US ABA mod-10 checksum |

```python
from gavio.interceptors.pii import (
    PiiGuard,
    RegexPolicyRule,
    core_policy_pack,
    custom_policy_pack,
    fintech_policy_pack,
    policy_pack_scanners,
)

pack = fintech_policy_pack()
print(pack.manifest()["id"])  # gavio.fintech

PiiGuard(scanners=policy_pack_scanners(core_policy_pack(), pack))

custom = custom_policy_pack(
    id="acme.internal",
    name="Acme Internal IDs",
    rules=[
        RegexPolicyRule(
            name="employee_id",
            entity_type="EMPLOYEE_ID",
            pattern=r"\bEMP-[0-9]{6}\b",
            confidence=0.88,
            replacement_prefix="EMPLOYEE_ID",
            action="flag",
            redaction_strategy="hash",
            label="INTERNAL_IDENTIFIER",
        )
    ],
    default_action="flag",
    redaction_strategy="hash",
    audit_labels=["INTERNAL_IDENTIFIER"],
)
```

Existing factory APIs remain compatible: `default_scanners()` and
`fintech_scanners()` in Python, `defaultScanners()` and `fintechScanners()` in
JavaScript, and `DefaultScanners.defaults()` / `DefaultScanners.fintech()` in
Java are now backed by the pack objects. JavaScript uses `corePolicyPack()`,
`fintechPolicyPack()`, `customPolicyPack()` and `policyPackScanners()`; Java
uses `PolicyPacks.core()`, `PolicyPacks.fintech()`, `PolicyPacks.custom()` and
`PolicyPacks.scanners(...)`.

Per-rule `confidence` still combines with `PiiGuard` sensitivity thresholds.
The action and redaction strategy are manifest metadata in this slice; runtime
blocking/redaction behavior is still controlled by `PiiGuard` mode.

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

### Right to erasure — GDPR Art. 17 (`F-QUA-09`)

Pass a `subject_id` in the request metadata and it is persisted on every
`AuditRecord`, so records can later be erased on a data-subject request:

```python
await gateway.complete(messages=[...], metadata={"subject_id": "user-123"})
```

Persistent sinks expose `purge(subject_id)`, which removes every matching
record and returns the count erased. The built-in `JsonlSink` (Python, Java,
and JavaScript) implements it by rewriting the file atomically:

```python
sink = JsonlSink("~/.gavio/audit.jsonl")
removed = await sink.purge("user-123")   # -> int records erased
```

**Scope.** Erasure covers records written to a **persistent, purgeable sink**
(the built-in `JsonlSink`). `StdoutSink` and any custom sink that does not
override `purge` return `0` — non-persistent or write-only destinations cannot
be purged, and downstream copies (log shippers, SIEMs, backups) are out of
scope and must be erased in those systems. Cached responses are **not** purged
by `subject_id` in this release. Tagging a record requires a `subject_id` on the
originating request; records written without one cannot be selected for erasure.

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
the same interface — see the [Python](./packages/python.md),
[JavaScript](./packages/javascript.md), or [Java](./packages/java.md) guide.

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

---

## v0.2.0 — Production core interceptors

The following ship in v0.2.0 across all three SDKs. API names below use the
Python style; JavaScript uses camelCase factory functions
(`semanticCache()`, `circuitBreaker()`, …) and Java uses builders
(`SemanticCache.builder()`, `CircuitBreaker.builder()`, …).

### Caching (`F-CACHE-01/02/03/04`)

`SemanticCache` is an `ExecutorPolicy` — a hit returns the cached response and
skips the provider. Register it **outermost**. Exact SHA-256 tier is always on;
pass an `embedder` to enable the semantic (cosine-similarity) tier.

```python
from gavio.interceptors.cache import SemanticCache, HashingEmbedder

SemanticCache(
    embedder=HashingEmbedder(),        # omit for exact-only
    similarity_threshold=0.95,
    exact_ttl_seconds=3600,
)
```

A cache hit sets `cache_hit` / `cache_type` on the response and composes with
`PiiGuard` (PII is still restored). The `HashingEmbedder` is zero-dependency;
plug in a real embedder implementing the `Embedder` protocol for production.

#### Redis backend (`F-CACHE-04`)

The default `MemoryBackend`/`InMemoryVectorBackend` are per-process; swap in a
Redis-backed pair to share cache hits across processes/instances. Entries are
namespaced under a Redis Set index, so `clear()` only removes keys that
backend itself wrote, never the whole database.

```python
from gavio.interceptors.cache import SemanticCache, HashingEmbedder
from gavio.interceptors.cache.backends import RedisBackend, RedisVectorBackend

SemanticCache(
    backend=RedisBackend(url="redis://localhost:6379"),
    embedder=HashingEmbedder(),
    vector_backend=RedisVectorBackend(url="redis://localhost:6379"),
)
```

Python requires the optional `redis` package (`pip install gavio[redis]`);
JavaScript (`redisCacheBackend()`/`redisVectorBackend()` from
`gavio/interceptors/cache/backends`) and Java (`new RedisCacheBackend(url)` /
`new RedisVectorBackend(url)` in `gavio-interceptor-cache`) hand-roll a
minimal RESP2 client over `node:net`/`java.net.Socket` — zero runtime
dependencies, no extra install needed. Also wired into the Python and
JavaScript config loaders (`semantic_cache.backend: redis`).

### Reliability — circuit breaker + load balancer (`F-REL-03/04`)

```python
from gavio.interceptors.reliability import CircuitBreaker, LoadBalancer

CircuitBreaker(failure_threshold=5, recovery_timeout_seconds=30)  # fast-fails while open
LoadBalancer([adapter_a, adapter_b], weights=[2, 1])              # weighted round-robin
```

Both are `ExecutorPolicy`. The breaker opens after N consecutive provider errors
and fast-fails with `CircuitOpenError`; the balancer distributes across a pool of
provider adapters.

### Governance (`F-GOV-02/03/04`)

```python
from gavio.interceptors.governance import CostControl, RateLimiter, ModelPolicy

CostControl(hard_cap_usd=50, soft_cap_usd=10, scope="agent", window="day")   # budget
RateLimiter(max_requests_per_minute=60, max_tokens_per_minute=100_000)       # rate limit
ModelPolicy(roles={"analyst": ["gpt-4o-mini"], "admin": ["*"]})              # RBAC
```

Budget blocks with `BudgetExceededError`, rate limiting with
`RateLimitExceededError`, and RBAC with `ModelNotAllowedError` (role read from
`request.metadata["role"]`).

In v0.11.0, `CostControl` can scope spend by `global`, `agent`, `session`,
`model`, `tenant`, `feature`, or `user`. Tenant/feature/user scopes are read
from `request.metadata.costDimensions` (or flat aliases like `tenant_id` and
`featureId`). A hard cap still blocks by default, or can fall back to a cheaper
model:

```python
CostControl(
    hard_cap_usd=50,
    soft_cap_usd=40,
    scope="tenant",
    window="month",
    fallback_model="gpt-4o-mini",
)
```

Soft warnings and hard-cap fallback/block decisions surface as Inspector
`governance.event` records with `kind="budget"`.

#### Cost-optimiser routing (`F-GOV-06`)

`CostRouter` reroutes a request to a cheaper `simple_model` when a pluggable
`ComplexityScorer` scores its prompt below `complexity_threshold`. The default
`HeuristicComplexityScorer` is zero-dependency — prompt length (via the same
token estimator `PricingProvider` uses) plus reasoning-keyword density
(`why`, `compare`, `trade-off`, `explain`, …).

```python
from gavio.interceptors.governance import CostRouter

CostRouter(simple_model="gpt-4o-mini", complexity_threshold=0.35)
```

#### Drift detection (`F-GOV-07`)

`DriftMonitor` watches a provider's response distribution and alerts when a
signal (latency, tokens, …) shifts away from its recent baseline. The default
`StatisticalDriftDetector` keeps a rolling window per metric and flags a sample
that deviates beyond a z-score `threshold`; supply your own `DriftDetector` to
change the algorithm.

```python
from gavio.interceptors.governance import DriftMonitor

DriftMonitor(metrics=["latency_ms", "total_tokens"], window_size=50, threshold=3.0)
```

Alerts are observe-only: each surfaces as a `governance.event` inspector event
and is counted in `driftAlerts` on `/api/stats`, and is logged. Nothing is
blocked or rerouted.

Register it early, before caching, so a rerouted request's cache key reflects
the model it actually ran on. Records its decision (`rerouted`,
`original_model`, `complexity_score`) in `ctx.state["cost_router"]` — the
audit trail's `model` field already reflects the rerouted model once it runs,
so no schema changes were needed.

### Guardrails (`F-QUA-01/02`)

```python
from gavio.interceptors.guardrails import GuardrailsInterceptor
from gavio.interceptors.guardrails.validators import (
    JsonSchemaValidator, RegexDenylistValidator, RegexAllowlistValidator,
)

GuardrailsInterceptor(
    validators=[JsonSchemaValidator({"type": "object", "required": ["answer"]}),
                RegexDenylistValidator([r"(?i)competitor"])],
    on_failure="error",   # error | retry | warn
)
```

An `ExecutorPolicy` that validates the response; on failure it raises
`GuardrailViolationError`, retries the provider, or warns. Records
`guardrail_outcome` for the audit trail.

### Prompt-injection defense (`F-SEC-05`)

```python
from gavio.interceptors.injection import PromptInjectionGuard

PromptInjectionGuard(action="block")   # or "flag"
```

Scans user/tool messages against a curated pattern corpus (Python/JS also
support an optional semantic tier via an `embedder`); blocks with
`PromptInjectionError` or flags by setting `risk_score`.

### Tamper-evident audit + multi-agent trace (`F-OBS-02/03`)

```python
from gavio.interceptors.audit import AuditInterceptor, verify_chain, build_call_graph

AuditInterceptor(hash_chain=True)   # links records via previous_hash
verify_chain(records)               # detects any edit/reorder/deletion
build_call_graph(records)           # reconstruct the multi-agent DAG
```

---

## v0.3.0 — Observability depth

Ships in v0.3.0 across all three SDKs. Python style shown; JavaScript uses
camelCase factories (`metricsInterceptor()`, `riskScorer()`) and Java uses
classes / builders (`new MetricsInterceptor()`, `new RiskScorer()`).

### Prompt lineage (`F-OBS-04`)

Attach the provenance of a prompt — the template, the variables bound into it,
and the RAG chunk **sources** — to a request. The audit interceptor copies it
onto the `AuditRecord` so any prompt can be reconstructed and debugged. RAG chunk
*text is never stored* — only source references — so the record stays
metadata-only.

```python
from gavio import PromptLineage, RagChunk

await gw.complete(
    messages=[...],
    lineage=PromptLineage(
        template_id="support-reply",
        template_version="v3",
        variables={"customer": "Ada", "tier": "gold"},
        rag_chunks=[RagChunk(source="doc://kb/refunds", chunk_id="c1", score=0.92)],
    ),
)
```

Lineage participates in the hash-chain `contentHash()`. Also threaded through
`Gateway.stream(...)`.

### Prometheus metrics (`F-OBS-08`)

`MetricsInterceptor` records per-request metrics into a `PrometheusMetrics`
registry; scrape it via `render()` for the Prometheus text exposition format —
no client library, zero dependencies.

```python
from gavio.interceptors.metrics import MetricsInterceptor

metrics = MetricsInterceptor()
gw = Gateway.builder().dev_mode(True).use(metrics).build()
# ...
print(metrics.metrics.render())
```

Emits `gavio_requests_total`, `gavio_tokens_total{kind}`, `gavio_cost_usd_total`,
`gavio_request_latency_ms` (histogram), and `gavio_cache_hits_total` — all
labelled by `provider` and `model`. (Python `gavio.interceptors.metrics`; JS
`gavio/interceptors/metrics`; Java module `gavio-interceptor-metrics`.)

### Risk scoring (`F-QUA-06`)

`RiskScorer` folds the per-request signals other interceptors leave on the
context — PII entities found, guardrail outcome (`FAIL`/`HITL`), and the
prompt-injection risk — into a single composite score in `[0, 1]`, written to
`ctx.risk_score` and recorded on the `AuditRecord`.

```python
from gavio.interceptors.quality import RiskScorer, RiskWeights

RiskScorer()                                   # default weights
RiskScorer(RiskWeights(pii=0.3, guardrail=0.4, injection=0.3, pii_saturation=4))
```

Register it **inside** the audit interceptor so audit sees the composite. Weights
are configurable and the composite is clamped. (New `quality` family: Python
`gavio.interceptors.quality`; JS `gavio/interceptors/quality`; Java module
`gavio-interceptor-quality`.)

### Streaming — StreamBuffer (`F-REL-06`)

`Gateway.stream(...)` drives the provider's streaming API but **buffers** the
response in full (via `StreamBuffer`) before the post-interceptor pipeline runs —
so guardrails, PII restore, and audit see, and can rewrite or block, the complete
response before any chunk reaches the caller.

```python
async for chunk in gw.stream(messages=[{"role": "user", "content": "hi"}]):
    print(chunk, end="")
```

Executor policies (retry, circuit breaker, cache) are not applied to the
streaming path in this release.
