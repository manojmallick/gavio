# Production Trust Package

Feature ID: `F-TRUST-01`
Since: `1.8.0`

The Production Trust Package is a metadata-only evidence bundle for release
reviews. It gives production, security, and compliance teams a single artifact
that says which Gavio controls ran, which evidence files back the release, and
whether the bundle itself has been tampered with.

The bundle is not an audit log replacement. It is a release-review summary that
points to audit chains, runtime event exports, benchmark output, policy packs,
eval reports, and architecture documents without storing raw prompt, response,
or tool content.

## Bundle Shape

The canonical schema is
[`spec/ProductionTrustBundle.schema.json`](../spec/ProductionTrustBundle.schema.json).

```json
{
  "schemaVersion": "1.0",
  "bundleId": "trust-prod-support-2026-07-12",
  "release": { "version": "2.0.0", "tag": "v2.0.0" },
  "runtime": {
    "environment": "production",
    "policySource": "project:prod-support",
    "eventExportMode": "metadata_only"
  },
  "evidence": {
    "auditChain": { "recordCount": 42, "verified": true },
    "runtimeEvents": { "eventCount": 168, "contentFree": true },
    "controls": [
      { "type": "policy_pack", "id": "support", "status": "pass" },
      { "type": "eval_suite", "id": "support-regression", "status": "pass" },
      { "type": "benchmark", "id": "inspector-overhead", "status": "pass" }
    ]
  },
  "bundleHash": "sha256:..."
}
```

`bundleHash` is the SHA-256 hash of the canonical bundle with the `bundleHash`
field excluded. Any edit to the release identity, runtime metadata, evidence,
privacy declaration, or document pointers changes the computed hash.

## Threat Model

The trust package is designed to answer four production-review questions:

| Question | Evidence |
|---|---|
| Did the runtime produce tamper-evident records? | `evidence.auditChain.verified` and head/tail hashes |
| Were exported events content-safe? | `privacy.contentMode`, `containsRawContent`, `redactedFields`, and `runtimeEvents.contentFree` |
| Which controls were included in this release? | `evidence.controls` entries for policy packs, eval suites, benchmarks, control-plane rollout, and release gates |
| Which docs explain the deployment posture? | `documents` pointers to threat model, reference architecture, and runbook sections |

The package does not prove that the application was configured correctly at
all times. It proves that a specific release review artifact has a consistent
metadata-only evidence set and an integrity hash.

## Privacy Boundary

Trust bundles reject content-bearing keys such as `messages`, `content`, `diff`,
`rawPrompt`, `rawResponse`, `inputText`, and `outputText`. Store raw content in
separate, access-controlled debugging systems only when your organization has
explicitly approved that workflow.

Recommended production default:

1. Keep runtime exporters in metadata mode.
2. Keep audit records hash-only.
3. Attach only hashes or paths for policy packs, eval reports, benchmark
   output, architecture docs, and release gates.
4. Verify the bundle before attaching it to a release, change request, or audit
   package.

## Reference Architecture

```text
Application
  -> Gavio Gateway
    -> PII / policy / budget / tool / eval controls
    -> metadata-only audit records and runtime events
      -> JSONL, OTel, or self-hosted control plane storage
        -> Production Trust Bundle
          -> release review, compliance evidence, incident review
```

The trust bundle sits above the runtime. It summarizes evidence that already
exists in audit chains, runtime event exports, control-plane snapshots, and
documentation.

## SDK Helpers

Python:

```python
from gavio import build_production_trust_bundle, verify_production_trust_bundle

bundle = build_production_trust_bundle(
    bundle_id="trust-prod-support-2026-07-12",
    generated_at="2026-07-12T12:00:00Z",
    release={"version": "2.5.0", "tag": "v2.5.0"},
    runtime={
        "environment": "production",
        "policySource": "project:prod-support",
        "eventExportMode": "metadata_only",
    },
    audit_records=audit_records,
)

assert verify_production_trust_bundle(bundle).valid
```

JavaScript:

```ts
import { buildProductionTrustBundle, verifyProductionTrustBundle } from 'gavio'

const bundle = buildProductionTrustBundle({
  bundleId: 'trust-prod-support-2026-07-12',
  generatedAt: '2026-07-12T12:00:00Z',
  release: { version: '2.5.0', tag: 'v2.5.0' },
  runtime: {
    environment: 'production',
    policySource: 'project:prod-support',
    eventExportMode: 'metadata_only',
  },
  auditRecords,
})

console.log(verifyProductionTrustBundle(bundle).valid)
```

Java:

```java
Map<String, Object> bundle = ProductionTrust.builder("trust-prod-support-2026-07-12")
    .generatedAt("2026-07-12T12:00:00Z")
    .release("2.5.0", "v2.5.0", commit)
    .runtime("production", "project:prod-support", true, "metadata_only")
    .auditChain(recordCount, chainOk, headHash, tailHash)
    .build();

ProductionTrustVerification result = ProductionTrust.verify(bundle);
```

Runnable examples live in
[`examples/python/14-production-trust`](../examples/python/14-production-trust/),
[`examples/javascript/14-production-trust`](../examples/javascript/14-production-trust/),
and [`examples/java/14-production-trust`](../examples/java/14-production-trust/).
