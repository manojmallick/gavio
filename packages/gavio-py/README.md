# Gavio — Python SDK

> AI request runtime and inspector for production systems. PII protection,
> audit trails, runtime events, reliability, cost intelligence, policy packs,
> production trust packages, platform runtime profiles, and provider adapters
> as composable interceptors.

`gavio` sits between your application and any LLM provider. The same request
passes through a pre/post interceptor chain — PII redaction, retries, caching,
budgets, audit logging, tool runtime, runtime events, runtime context — before and after the provider call. Same API in
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

## Tool Runtime

```python
from gavio.interceptors.tool_runtime import ToolRuntimeInterceptor

gw = Gateway.builder().dev_mode(True).use(ToolRuntimeInterceptor()).build()
```

Tool Runtime reads `metadata["tools"]["calls"]`, validates declared input/output
schemas, checks result freshness, detects configured conflicts, and records
provenance in `ctx.tools["runtime"]`. Tool Runtime v2 also understands
`definitions`, `permissions`, `approvals`, `records`, and MCP metadata.

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

## Runtime export

```python
from gavio import Gateway, JsonlRuntimeExporter, OtelSpanExporter

gw = (
    Gateway.builder()
    .dev_mode(True)
    .exporter(JsonlRuntimeExporter("runtime-events.jsonl"))
    .exporter(OtelSpanExporter("otel-spans.jsonl", service_name="checkout-api"))
    .build()
)
```

Runtime export (v1.1.0) writes metadata-safe JSONL events for integrations. The
exporter strips `messages`, `content`, and `diff` by default, even when the
local Inspector is in full capture mode. Observability + OTel (v1.3.0) maps
the same stream into OpenTelemetry-style span JSON (`F-OBS-07`).

## Ecosystem integrations

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

Ecosystem integration helpers (v1.9.0, `F-INT-01`) provide dependency-light
metadata labels and compatibility rows for common gateways, observability
tools, eval tools, frameworks, and provider SDKs. Ecosystem adapter helpers
(v2.5.0, `F-INT-02`) add metadata-only payload fragments for LiteLLM,
promptfoo, Langfuse, OpenLIT, LangChain, LangGraph, and the Vercel AI SDK.

## Platform Runtime Profile

```python
from gavio import build_platform_runtime_profile

profile = build_platform_runtime_profile(
    profile_id="platform-prod-support",
    generated_at="2026-07-12T12:00:00Z",
    runtime={"environment": "production", "eventExportMode": "metadata_only"},
    surfaces=[
        "runtime_events",
        "audit_hashes",
        "policy_packs",
        "cost_governance",
        "tool_runtime",
        "trust_evidence",
    ],
)
```

Platform Runtime Profile support (v2.0.0, `F-PLAT-01`) creates deterministic,
metadata-only readiness reports for production runtime posture.

## Self-hosted Control Plane

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

Control Plane support (v1.7.0) loads runtime config from an optional
self-hosted server, caches the last successful config, and can fail open or
closed during outages. v2.3.0 adds durable JSON file, SQLite, and Postgres
storage modes to the control-plane app. The same surface is available as
`ControlPlaneClient` and `load_control_plane_config`.

## Production Trust Package

```python
from gavio import build_production_trust_bundle, verify_production_trust_bundle

bundle = build_production_trust_bundle(
    bundle_id="trust-prod-support-2026-07-12",
    generated_at="2026-07-12T12:00:00Z",
    release={"version": "2.7.0", "tag": "v2.7.0"},
    runtime={
        "environment": "production",
        "policySource": "project:prod-support",
        "eventExportMode": "metadata_only",
    },
    audit_records=audit_records,
)

assert verify_production_trust_bundle(bundle).valid
```

Production Trust Package support (v1.8.0, `F-TRUST-01`) creates deterministic,
metadata-only release evidence bundles for audit-chain, runtime-event, policy,
benchmark, and document review.

## Prompt Registry + Evals

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

report = await EvalSuite.from_dict({
    "id": "support-smoke",
    "cases": [{
        "id": "refund",
        "templateId": "support.reply",
        "variables": {"customer": "Avery", "topic": "refund"},
        "assertions": [{"type": "contains", "value": "refund"}],
    }],
}).run(registry, lambda _prompt, _case: "Avery refund approved")
```

Prompt Registry + Evals (v1.4.0) adds versioned prompt templates,
metadata-only lineage, deterministic pass/fail reports, and SHA-256 output
hashes instead of raw model output (`F-EVAL-01/02`).

Python v2.2.0 adds Prompt Registry v2 manifests with signed file loading,
semantic-version selectors, approval metadata, and metadata-safe diffs
(`F-EVAL-04`).

Python v2.4.0 adds prompt-to-eval links, per-version regression gates, failure
triage metadata, and prompt release bundles (`F-EVAL-05`).

Python v2.1.0 also adds `gavio eval run` for CI gates over JSON/YAML suites:

```bash
gavio eval run examples/python/21-eval-ci-gate/suite.yaml \
  --baseline examples/python/21-eval-ci-gate/baseline-report.json \
  --fail-under 0.95 \
  --max-regression 0.02 \
  --report reports/gavio-eval-report.json \
  --junit reports/gavio-eval-junit.xml \
  --summary
```

## What's inside

Every feature is an interceptor you compose explicitly — no hidden magic.

- **Privacy & security** — PII Guard with Email, IBAN (mod-97), BSN (11-proef),
  CreditCard (Luhn), Phone, IP, SSN scanners and redact/mask/tag/block +
  restore (`F-SEC-01`); secret/credential scanner (`F-SEC-04`); prompt
  injection guard (`F-SEC-05`); embedding call guard (`F-SEC-10`); Policy Pack
  manifests for core, FinTech, custom regex-rule packs, and the signed domain
  catalog with load/override/signature APIs (`F-PACK-01/02/05`).
- **Reliability** — retry with backoff (`F-REL-01`), provider fallback chain
  (`F-REL-02`), circuit breaker (`F-REL-03`), load balancing (`F-REL-04`),
  buffered streaming (`F-REL-06`), timeouts (`F-REL-07`).
- **Caching** — SHA-256 exact + semantic (cosine) cache with in-memory and
  Redis backends (`F-CACHE-01/02/03/04`).
- **Cost & governance** — per-request cost tracking (`F-GOV-01`), budget caps
  (`F-GOV-02`), rate limiting (`F-GOV-03`), per-role model policy (`F-GOV-04`),
  cost-optimiser routing (`F-GOV-06`), Cost Governance v2 budget policies,
  decisions, reports, and `gavio cost report` (v1.2.0).
- **Observability** — audit-by-default with SHA-256 content hashes, never raw
  text (`F-OBS-01`), tamper-evident hash chain (`F-OBS-02`), multi-agent DAG
  tracing via `agent_id`/`parent_trace_id` (`F-OBS-03`), prompt lineage
  (`F-OBS-04`), Prometheus metrics (`F-OBS-08`), stdout + JSONL sinks.
- **Prompt Registry + Evals** — versioned templates, lineage-preserving render,
  deterministic eval cases, privacy-safe output hashes, `gavio eval run` CI
  gates, signed manifests, semantic-version selectors, approvals, and
  metadata-safe prompt diffs, prompt-to-eval links, triage metadata, and prompt
  release bundles (`F-EVAL-01/02/03/04/05`).
- **Runtime export** — metadata-safe JSONL runtime events (`F-EXP-01`) and
  OpenTelemetry-style span JSON (`F-OBS-07`) for gateway, observability, and
  eval integrations.
- **Control Plane** — optional self-hosted runtime config with policy rollout,
  budget config, audit search, config snapshots, SDK cache fallback, durable
  SQLite/Postgres app storage, and `ControlPlaneClient` (v1.7.0).
- **Production Trust Package** — metadata-only release evidence bundles with
  deterministic hashes, privacy checks, audit-chain evidence, runtime-event
  evidence, and document/control pointers (`F-TRUST-01`).
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
