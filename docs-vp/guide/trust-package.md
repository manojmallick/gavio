---
description: "Production Trust Package - metadata-only release evidence bundles for audit chains, runtime events, controls, benchmarks, and architecture docs."
---

# Production Trust Package

Since: `1.8.0` · Feature ID: `F-TRUST-01`

The Production Trust Package creates a metadata-only evidence bundle for release
reviews. It summarizes audit-chain verification, runtime event export posture,
policy/eval/benchmark controls, release identity, and reference docs without
storing raw prompt, response, or tool content.

## What It Contains

| Section | Purpose |
|---|---|
| `release` | Version, tag, and commit under review |
| `runtime` | Environment, policy source, control-plane posture, export mode |
| `privacy` | Metadata-only declaration and stripped content fields |
| `evidence.auditChain` | Record count, verified flag, head hash, tail hash |
| `evidence.runtimeEvents` | Event count, content-free flag, emitted event types |
| `evidence.controls` | Policy packs, eval suites, benchmarks, release gates |
| `documents` | Threat model, reference architecture, and runbook pointers |
| `bundleHash` | SHA-256 over the canonical bundle without `bundleHash` |

## Privacy Boundary

Trust bundles reject content-bearing keys such as `messages`, `content`, `diff`,
`rawPrompt`, `rawResponse`, `inputText`, and `outputText`. Attach hashes,
statuses, paths, and counters to the bundle; keep raw content out of release
evidence by default.

## Python

```python
from gavio import build_production_trust_bundle, verify_production_trust_bundle

bundle = build_production_trust_bundle(
    bundle_id="trust-prod-support-2026-07-12",
    generated_at="2026-07-12T12:00:00Z",
    release={"version": "3.1.0", "tag": "v3.1.0"},
    runtime={
        "environment": "production",
        "policySource": "project:prod-support",
        "eventExportMode": "metadata_only",
    },
    audit_records=audit_records,
)

assert verify_production_trust_bundle(bundle).valid
```

## JavaScript

```ts
import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio'

const bundle = buildProductionTrustBundle({
  bundleId: 'trust-prod-support-2026-07-12',
  generatedAt: '2026-07-12T12:00:00Z',
  release: { version: '3.1.0', tag: 'v3.1.0' },
  runtime: {
    environment: 'production',
    policySource: 'project:prod-support',
    eventExportMode: 'metadata_only',
  },
  auditRecords,
})

console.log(verifyProductionTrustBundle(bundle).valid)
```

## Java

```java
Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
    .generatedAt("2026-07-12T12:00:00Z")
    .release("3.1.0", "v3.1.0", commit)
    .runtime("production", "project:prod-support", true, "metadata_only")
    .auditChain(recordCount, chainOk, headHash, tailHash)
    .build();

ProductionTrustVerification result = ProductionTrust.verify(bundle);
```

The canonical shape is `spec/ProductionTrustBundle.schema.json`, with shared
vectors in `test-vectors/trust/production-trust-bundle.json`.
