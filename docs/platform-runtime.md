# Platform Runtime Profile

Since: `2.0.0`  
Feature ID: `F-PLAT-01`

The Platform Runtime Profile is a metadata-only readiness report for production
Gavio deployments. It answers a narrow question: does this application runtime
have the core surfaces expected from a platform-grade embedded AI runtime?

It does not store raw prompts, responses, tool inputs, or tool outputs. It
records enabled runtime surfaces, exporters, integrations, control statuses,
audit-chain evidence, runtime-event evidence, and deterministic readiness gaps.

## Readiness Checks

By default, production readiness requires these surfaces:

| Surface | Why it matters |
|---|---|
| `runtime_events` | Exportable trace and decision metadata |
| `audit_hashes` | Tamper-evident request/response evidence |
| `policy_packs` | Domain and organization policy controls |
| `cost_governance` | Budget and spend controls |
| `tool_runtime` | Tool permission, approval, and replay checks |
| `trust_evidence` | Release-review evidence and benchmark/document pointers |

The profile also checks that runtime events are metadata-only, audit-chain
evidence is verified, and no included control reports `fail`.

## Python

```python
from gavio import build_platform_runtime_profile, verify_platform_runtime_profile

profile = build_platform_runtime_profile(
    profile_id="platform-prod-support",
    generated_at="2026-07-12T12:00:00Z",
    sdk={"name": "gavio", "version": "3.0.0"},
    runtime={
        "environment": "production",
        "provider": "openai",
        "model": "gpt-4o",
        "eventExportMode": "metadata_only",
        "controlPlaneEnabled": True,
        "policySource": "project:prod-support",
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
assert profile["readiness"]["ready"]
```

## JavaScript

```ts
import { buildPlatformRuntimeProfile, verifyPlatformRuntimeProfile } from 'gavio/platform-runtime'

const profile = buildPlatformRuntimeProfile({
  profileId: 'platform-prod-support',
  generatedAt: '2026-07-12T12:00:00Z',
  sdk: { name: 'gavio', version: '3.0.0' },
  runtime: {
    environment: 'production',
    eventExportMode: 'metadata_only',
    controlPlaneEnabled: true,
  },
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

console.log(verifyPlatformRuntimeProfile(profile).valid)
```

## Java

```java
import io.gavio.platform.PlatformRuntime;
import io.gavio.platform.PlatformRuntimeVerification;

Map<String, Object> profile = PlatformRuntime.builder("platform-prod-support")
    .generatedAt("2026-07-12T12:00:00Z")
    .sdk("gavio", "3.0.0")
    .runtime(Map.of(
        "environment", "production",
        "eventExportMode", "metadata_only",
        "controlPlaneEnabled", true))
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

PlatformRuntimeVerification result = PlatformRuntime.verify(profile);
```

The canonical profile shape is
[`spec/PlatformRuntimeProfile.schema.json`](../spec/PlatformRuntimeProfile.schema.json).
Shared vectors live in
[`test-vectors/platform-runtime/profile.json`](../test-vectors/platform-runtime/profile.json).
