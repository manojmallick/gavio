# Python SDK (`gavio`)

> PyPI package `gavio` · Python 3.10+ · async-first · zero mandatory dependencies

The Python SDK is the **reference implementation**. Source:
[`packages/gavio-py`](../../packages/gavio-py/).

- [Install](#install)
- [Gateway API](#gateway-api)
- [Interceptors](#interceptors)
- [Providers](#providers)
- [Runtime export](#runtime-export)
- [Ecosystem Integrations](#ecosystem-integrations)
- [Platform Runtime Profile](#platform-runtime-profile)
- [Production Trust Package](#production-trust-package)
- [Prompt Registry + Evals](#prompt-registry--evals)
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
`.exporter(JsonlRuntimeExporter(...))`, `.exporter(OtelSpanExporter(...))`.

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

### Tool Runtime

`ToolRuntimeInterceptor` validates tool metadata from `metadata["tools"]` before
tool outputs re-enter model context. It supports declared input/output schemas,
freshness/TTL checks, conflict detection across configured result keys,
confidence scoring, and provenance records under `ctx.tools["runtime"]`.
Tool Runtime v2 adds registry-backed permissions, approval gates, replay
records, and MCP metadata capture through the same `metadata["tools"]` object.

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

Observability + OTel (v1.3.0, `F-OBS-07`) maps the same runtime events into
OpenTelemetry-style span JSON without adding mandatory OTel dependencies:

```python
from gavio import Gateway, OtelSpanExporter

gw = (
    Gateway.builder()
    .exporter(OtelSpanExporter("otel-spans.jsonl", service_name="checkout-api"))
    .build()
)
```

Existing runtime-event JSONL can be converted through the CLI:

```bash
gavio events convert --from runtime-events.jsonl --to otel-json --service-name checkout-api
```

## Ecosystem Integrations

Ecosystem integration helpers (v1.9.0, `F-INT-01`) provide dependency-light
metadata labels and compatibility rows for common gateways, observability
tools, eval tools, frameworks, and provider SDKs. Ecosystem adapter helpers
(v2.5.0, `F-INT-02`) add metadata-only payload fragments for LiteLLM,
promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and the Vercel AI SDK.

```python
from gavio import compatibility_matrix, integration_adapter_payload, integration_metadata

metadata = integration_metadata(
    "litellm",
    tenant="acme",
    feature="support-chat",
    environment="prod",
)
rows = compatibility_matrix()
adapter = integration_adapter_payload(
    "litellm",
    {"traceId": "trace_123", "data": {"status": "ok", "provider": "openai"}},
    metadata={**metadata, "prompt": "raw prompt text"},
)
```

## Platform Runtime Profile

Platform Runtime Profile support (v2.0.0, `F-PLAT-01`) summarizes production
readiness across runtime events, audit hashes, policy packs, cost governance,
tool runtime, and trust evidence without storing prompts or responses.

```python
from gavio import build_platform_runtime_profile, verify_platform_runtime_profile

profile = build_platform_runtime_profile(
    profile_id="platform-prod-support",
    generated_at="2026-07-12T12:00:00Z",
    runtime={
        "environment": "production",
        "policySource": "project:prod-support",
        "eventExportMode": "metadata_only",
    },
    surfaces=[
        "runtime_events",
        "audit_hashes",
        "policy_packs",
        "cost_governance",
        "tool_runtime",
        "trust_evidence",
    ],
    evidence={
        "auditChain": {"recordCount": 42, "verified": True},
        "runtimeEvents": {"eventCount": 168, "contentFree": True},
    },
)

assert verify_platform_runtime_profile(profile).valid
```

See [Platform Runtime Profile](../platform-runtime.md) for the schema,
readiness scoring contract, and cross-SDK test vector.

## Self-hosted Control Plane

Control Plane support (v1.7.0) loads runtime config from an optional
self-hosted server and caches the last successful config for offline
fail-open/fail-closed behavior. v2.3.0 adds durable JSON file, SQLite, and
Postgres storage modes to the control-plane app. v2.6.0 adds Enterprise Admin
v2 controls in the app while keeping the SDK runtime config contract unchanged.

```python
from gavio import Gateway

gw = (
    Gateway.builder()
    .dev_mode(True)
    .control_plane(
        "http://127.0.0.1:8787",
        runtime_key,
        "project:prod-support",
        fail_mode="open",
    )
    .build()
)
```

Use `ControlPlaneClient` or `load_control_plane_config` directly when you need
to inspect or preload the fetched config before constructing a gateway.

## Production Trust Package

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

```python
from gavio import build_production_trust_bundle, verify_production_trust_bundle

bundle = build_production_trust_bundle(
    bundle_id="trust-prod-support-2026-07-12",
    generated_at="2026-07-12T12:00:00Z",
    release={"version": "2.6.0", "tag": "v2.6.0"},
    runtime={
        "environment": "production",
        "policySource": "project:prod-support",
        "eventExportMode": "metadata_only",
    },
    audit_records=audit_records,
)

assert verify_production_trust_bundle(bundle).valid
```

See [Production Trust Package](../trust-package.md) for the bundle schema,
threat model, privacy boundary, and cross-SDK examples.

## Prompt Registry + Evals

Prompt Registry + Evals (v1.4.0, `F-EVAL-01/02`) renders versioned chat
templates with metadata-only `PromptLineage` and runs deterministic eval cases
without storing raw model output in reports.

```python
from gavio import EvalSuite, PromptRegistry, PromptTemplate

registry = PromptRegistry([
    PromptTemplate(
        id="support.reply",
        version="2026-07-12",
        messages=[
            {"role": "system", "content": "You are concise."},
            {"role": "user", "content": "Reply to {{ customer }} about {{ topic }}."},
        ],
        required_variables=("customer", "topic"),
    )
])

suite = EvalSuite.from_dict({
    "id": "support-smoke",
    "cases": [{
        "id": "refund",
        "templateId": "support.reply",
        "variables": {"customer": "Avery", "topic": "refund"},
        "assertions": [{"type": "contains", "value": "refund"}],
    }],
})

report = await suite.run(registry, lambda _prompt, _case: "Avery refund approved")
assert report.score == 1.0
```

Python v2.1.0 adds a file-backed CI runner for the same deterministic eval
contract:

```bash
gavio eval run examples/python/21-eval-ci-gate/suite.yaml \
  --baseline examples/python/21-eval-ci-gate/baseline-report.json \
  --fail-under 0.95 \
  --max-regression 0.02 \
  --report reports/gavio-eval-report.json \
  --junit reports/gavio-eval-junit.xml \
  --summary
```

Use JSON suites by default, or install `gavio[yaml]` for full YAML support. The
runner exits `1` when cases fail, the score falls below `--fail-under`, or the
baseline regression exceeds `--max-regression`.

Python v2.4.0 adds prompt-to-eval links, per-version regression gates, failure
triage metadata, and prompt release bundles for release evidence.

See [Prompt Registry + Evals](../prompt-registry-evals.md) for all SDKs and the
shared schemas.

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
