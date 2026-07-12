# Gavio documentation

Start here, then dive into the language you use.

## Core

- [Getting started](./getting-started.md) — 5-minute quickstart in all three languages
- [Architecture](./architecture.md) — request lifecycle, interceptor chain, data model
- [Interceptors](./interceptors.md) — every built-in + writing your own
- [Inspector](./inspector.md) — dev-time visualizer: live traces, waterfalls, PII diffs; v0.7.0 adds agent DAGs, replay, and the read-only dashboard
- [Runtime events](./runtime-events.md) — metadata-safe runtime event/export contract, JSONL exporter, and OTel span bridge
- [Prompt Registry + Evals](./prompt-registry-evals.md) — versioned prompt templates, metadata-safe eval reports, and CI gates
- [Self-hosted Control Plane](./control-plane.md) — local project, key, policy, budget, audit search, and config snapshot APIs
- [Production Trust Package](./trust-package.md) — metadata-only release evidence bundles and verification
- [Integrations](./integrations.md) — how Gavio fits beside gateways, observability, and eval tools
- [Platform Runtime Profile](./platform-runtime.md) — metadata-only readiness score and production posture gaps
- [Stability](./stability.md) — API stability, LTS policy, and stable release gate

## Per-package guides

- [Python](./packages/python.md) — `gavio` on PyPI
- [JavaScript / TypeScript](./packages/javascript.md) — `gavio` on npm
- [Java](./packages/java.md) — `io.github.manojmallick:gavio-*` on Maven Central

## Reference

- [`spec/`](../spec/) — canonical JSON Schema data model
- [`test-vectors/`](../test-vectors/) — shared cross-SDK conformance cases
- [Runtime event schema](../spec/GavioRuntimeEvent.schema.json) — public export envelope
- [OTel span schema](../spec/GavioOtelSpan.schema.json) — OpenTelemetry-style span JSON emitted from runtime events
- [Prompt template schema](../spec/PromptTemplate.schema.json) — versioned chat template contract
- [Eval report schema](../spec/EvalReport.schema.json) — metadata-safe eval report contract
- [Control-plane runtime config schema](../spec/ControlPlaneRuntimeConfig.schema.json) — SDK config fetched from the self-hosted control plane
- [Control-plane event schema](../spec/ControlPlaneEvent.schema.json) — metadata-only runtime events accepted by the self-hosted control plane
- [Production trust bundle schema](../spec/ProductionTrustBundle.schema.json) — metadata-only release evidence bundle contract
- [Platform runtime profile schema](../spec/PlatformRuntimeProfile.schema.json) — metadata-only readiness profile contract
- [OTel mapping](./otel-mapping.md) — InspectorEvent → OpenTelemetry spans · [Grafana dashboard](./grafana/gavio-dashboard.json)
- [CHANGELOG](../CHANGELOG.md) — release history + feature IDs
- [Examples](../examples/) — runnable focused projects, eval CI gates, and the v2.0.0 platform feature tour
- [RELEASING](../RELEASING.md) — how releases are cut
- [CONTRIBUTING](../CONTRIBUTING.md) — contribution guide
