"""Offline Platform Workflow Release example."""

from __future__ import annotations

import json
from pathlib import Path

from gavio import (
    build_platform_runtime_profile,
    build_production_trust_bundle,
    run_platform_workflow_release_file,
)


HERE = Path(__file__).resolve().parent


def main() -> None:
    trust = build_production_trust_bundle(
        bundle_id="trust-platform-workflow",
        generated_at="2026-07-12T12:00:00Z",
        release={"version": "3.1.0", "tag": "v3.1.0", "commit": "local-example"},
        runtime={"environment": "production", "policySource": "project:prod-support"},
        audit_chain_verified=True,
        runtime_events=[],
        controls=[{"id": "release-gate", "type": "release_gate", "status": "pass"}],
    )
    (HERE / "trust.json").write_text(json.dumps(trust, indent=2) + "\n", encoding="utf-8")

    profile = build_platform_runtime_profile(
        profile_id="platform-prod-support",
        generated_at="2026-07-12T12:00:00Z",
        sdk={"name": "gavio", "version": "3.1.0"},
        runtime={
            "environment": "production",
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
        controls=[{"id": "support-policy", "type": "policy_pack", "status": "pass"}],
        evidence={
            "auditChain": {"recordCount": 1, "verified": True},
            "runtimeEvents": {"eventCount": 2, "contentFree": True},
        },
    )
    (HERE / "profile.json").write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")

    result = run_platform_workflow_release_file(HERE / "workflow.json")
    (HERE / "workflow-release.json").write_text(
        json.dumps(result.artifact, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"passed": result.passed, "workflowHash": result.artifact["workflowHash"]}))


if __name__ == "__main__":
    main()

