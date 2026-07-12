from __future__ import annotations

import json
from pathlib import Path

from gavio import build_production_trust_bundle, verify_production_trust_bundle
from gavio.interceptors.audit import AuditRecord

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


def _records() -> list[AuditRecord]:
    first = AuditRecord(
        trace_id="trace-a",
        provider="mock",
        model="mock",
        timestamp_utc="2026-07-12T12:00:00Z",
        prompt_hash=AuditRecord.hash_text("prompt-a"),
        response_hash=AuditRecord.hash_text("response-a"),
    )
    second = AuditRecord(
        trace_id="trace-b",
        provider="mock",
        model="mock",
        timestamp_utc="2026-07-12T12:00:01Z",
        previous_hash=first.content_hash(),
        prompt_hash=AuditRecord.hash_text("prompt-b"),
        response_hash=AuditRecord.hash_text("response-b"),
    )
    return [first, second]


def test_builds_and_verifies_metadata_only_trust_bundle() -> None:
    bundle = build_production_trust_bundle(
        bundle_id="trust-prod-support-2026-07-12",
        generated_at="2026-07-12T12:00:00Z",
        sdk={"name": "gavio-python", "version": "1.9.0"},
        release={"version": "1.9.0", "tag": "v1.9.0", "commit": "b1ff1be"},
        runtime={
            "environment": "production",
            "policySource": "project:prod-support",
            "controlPlaneEnabled": True,
            "eventExportMode": "metadata_only",
        },
        audit_records=_records(),
        runtime_events=[
            {"type": "trace.start", "data": {"provider": "mock"}},
            {"type": "provider.call.end", "data": {"status": "ok"}},
        ],
        controls=[
            {
                "type": "policy_pack",
                "id": "support",
                "status": "pass",
                "source": "test-vectors/policy-packs/catalog.json",
            }
        ],
        documents=[{"name": "Threat model", "path": "docs/trust-package.md#threat-model"}],
    )

    result = verify_production_trust_bundle(bundle)

    assert result.valid
    assert result.errors == []
    assert bundle["bundleHash"] == result.computed_hash
    assert bundle["evidence"]["auditChain"]["recordCount"] == 2
    assert bundle["evidence"]["runtimeEvents"]["contentFree"] is True


def test_verifies_shared_production_trust_vector() -> None:
    vector = json.loads((_VECTORS / "trust" / "production-trust-bundle.json").read_text())

    result = verify_production_trust_bundle(vector)

    assert result.valid
    assert result.computed_hash == vector["bundleHash"]


def test_rejects_tampered_or_content_bearing_bundle() -> None:
    bundle = build_production_trust_bundle(
        bundle_id="trust-prod-support-2026-07-12",
        generated_at="2026-07-12T12:00:00Z",
        sdk={"name": "gavio-python", "version": "1.9.0"},
        release={"version": "1.9.0"},
        runtime={
            "environment": "production",
            "policySource": "project:prod-support",
            "eventExportMode": "metadata_only",
        },
        audit_records=_records(),
    )
    bundle["release"]["version"] = "1.7.1"
    bundle["evidence"]["runtimeEvents"]["contentFree"] = False
    bundle["evidence"]["runtimeEvents"]["content"] = "raw prompt text"

    result = verify_production_trust_bundle(bundle)

    assert not result.valid
    assert "bundleHash does not match bundle content" in result.errors
    assert "bundle contains content-bearing keys" in result.errors
    assert "evidence.runtimeEvents.contentFree must be true" in result.errors
