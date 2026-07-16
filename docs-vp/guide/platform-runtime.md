---
description: "Platform Runtime Profile - metadata-only readiness profiles for platform-grade Gavio deployments."
---

# Platform Runtime Profile

Since: `2.0.0` · Feature ID: `F-PLAT-01`

The Platform Runtime Profile is a metadata-only readiness report for production
Gavio deployments. It records enabled runtime surfaces, exporters,
integrations, control statuses, audit-chain evidence, runtime-event evidence,
and deterministic readiness gaps without storing raw prompt, response, or tool
content.

Default platform readiness requires:

| Surface | Purpose |
|---|---|
| `runtime_events` | Exportable trace and decision metadata |
| `audit_hashes` | Tamper-evident request/response evidence |
| `policy_packs` | Domain and organization policy controls |
| `cost_governance` | Budget and spend controls |
| `tool_runtime` | Tool permission, approval, and replay checks |
| `trust_evidence` | Release-review evidence and benchmark/document pointers |

## Python

```python
from gavio import build_platform_runtime_profile, verify_platform_runtime_profile

profile = build_platform_runtime_profile(
    profile_id="platform-prod-support",
    generated_at="2026-07-12T12:00:00Z",
    sdk={"name": "gavio", "version": "3.1.0"},
    runtime={"environment": "production", "eventExportMode": "metadata_only"},
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

## JavaScript

```ts
import { buildPlatformRuntimeProfile } from 'gavio/platform-runtime'

const profile = buildPlatformRuntimeProfile({
  profileId: 'platform-prod-support',
  generatedAt: '2026-07-12T12:00:00Z',
  sdk: { name: 'gavio', version: '3.1.0' },
  runtime: { environment: 'production', eventExportMode: 'metadata_only' },
  surfaces: [
    'runtime_events',
    'audit_hashes',
    'policy_packs',
    'cost_governance',
    'tool_runtime',
    'trust_evidence',
  ],
  evidence: {
    auditChain: { recordCount: 42, verified: true },
    runtimeEvents: { eventCount: 168, contentFree: true },
  },
})
```

## Java

```java
Map<String, Object> profile = PlatformRuntime.builder("platform-prod-support")
    .generatedAt("2026-07-12T12:00:00Z")
    .sdk("gavio", "3.1.0")
    .runtime(Map.of("environment", "production", "eventExportMode", "metadata_only"))
    .surfaces(List.of(
        "runtime_events",
        "audit_hashes",
        "policy_packs",
        "cost_governance",
        "tool_runtime",
        "trust_evidence"))
    .evidence(Map.of(
        "auditChain", Map.of("recordCount", 42, "verified", true),
        "runtimeEvents", Map.of("eventCount", 168, "contentFree", true)))
    .build();
```

The canonical shape is `spec/PlatformRuntimeProfile.schema.json`, with shared
vectors in `test-vectors/platform-runtime/profile.json`.
