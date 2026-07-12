# Gavio documentation

Start here, then dive into the language you use.

## Core

- [Getting started](./getting-started.md) — 5-minute quickstart in all three languages
- [Architecture](./architecture.md) — request lifecycle, interceptor chain, data model
- [Interceptors](./interceptors.md) — every built-in + writing your own
- [Inspector](./inspector.md) — dev-time visualizer: live traces, waterfalls, PII diffs; v0.7.0 adds agent DAGs, replay, and the read-only dashboard
- [Stability](./stability.md) — API stability, LTS policy, and stable release gate

## Per-package guides

- [Python](./packages/python.md) — `gavio` on PyPI
- [JavaScript / TypeScript](./packages/javascript.md) — `gavio` on npm
- [Java](./packages/java.md) — `io.github.manojmallick:gavio-*` on Maven Central

## Reference

- [`spec/`](../spec/) — canonical JSON Schema data model
- [`test-vectors/`](../test-vectors/) — shared cross-SDK conformance cases
- [OTel mapping](./otel-mapping.md) — InspectorEvent → OpenTelemetry spans · [Grafana dashboard](./grafana/gavio-dashboard.json)
- [CHANGELOG](../CHANGELOG.md) — release history + feature IDs
- [RELEASING](../RELEASING.md) — how releases are cut
- [CONTRIBUTING](../CONTRIBUTING.md) — contribution guide
