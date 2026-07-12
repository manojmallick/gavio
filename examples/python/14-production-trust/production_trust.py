"""Gavio Production Trust Package demo.

    pip install -r requirements.txt
    python production_trust.py
"""

from gavio import build_production_trust_bundle, verify_production_trust_bundle
from gavio.interceptors.audit import AuditRecord


def main() -> None:
    first = AuditRecord(
        trace_id="trace-a",
        provider="mock",
        model="mock",
        timestamp_utc="2026-07-12T12:00:00Z",
        prompt_hash=AuditRecord.hash_text("support question"),
        response_hash=AuditRecord.hash_text("support answer"),
    )
    second = AuditRecord(
        trace_id="trace-b",
        provider="mock",
        model="mock",
        timestamp_utc="2026-07-12T12:00:01Z",
        previous_hash=first.content_hash(),
        prompt_hash=AuditRecord.hash_text("handoff question"),
        response_hash=AuditRecord.hash_text("handoff answer"),
    )

    bundle = build_production_trust_bundle(
        bundle_id="trust-prod-support-2026-07-12",
        generated_at="2026-07-12T12:00:00Z",
        release={"version": "2.0.0", "tag": "v2.0.0", "commit": "b1ff1be"},
        runtime={
            "environment": "production",
            "policySource": "project:prod-support",
            "controlPlaneEnabled": True,
            "eventExportMode": "metadata_only",
        },
        audit_records=[first, second],
        runtime_events=[
            {"type": "trace.start", "data": {"provider": "mock"}},
            {"type": "provider.call.end", "data": {"status": "ok"}},
            {"type": "trace.end", "data": {"status": "ok", "costUsd": 0.0}},
        ],
        controls=[
            {
                "type": "policy_pack",
                "id": "support",
                "status": "pass",
                "source": "test-vectors/policy-packs/catalog.json",
            },
            {
                "type": "benchmark",
                "id": "inspector-overhead",
                "status": "pass",
                "source": "docs/gavio-1x-gap-closure-roadmap.md",
            },
        ],
        documents=[
            {
                "name": "Threat model",
                "path": "docs/trust-package.md#threat-model",
            }
        ],
    )
    result = verify_production_trust_bundle(bundle)

    print("bundle:", bundle["bundleId"])
    print("hash  :", bundle["bundleHash"])
    print("valid :", result.valid)
    print("events:", ", ".join(bundle["evidence"]["runtimeEvents"]["eventTypes"]))


if __name__ == "__main__":
    main()
