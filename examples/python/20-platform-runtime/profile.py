from __future__ import annotations

import json

from gavio import build_platform_runtime_profile, verify_platform_runtime_profile


profile = build_platform_runtime_profile(
    profile_id="platform-prod-support",
    generated_at="2026-07-12T12:00:00Z",
    sdk={"name": "gavio", "version": "2.1.0"},
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
        "integration_catalog",
    ],
    exporters=["jsonl", "otel"],
    integrations=["litellm", "openlit", "promptfoo"],
    controls=[
        {
            "id": "support-policy",
            "type": "policy_pack",
            "status": "pass",
            "source": "policy-packs/support/manifest.json",
        },
        {
            "id": "monthly-budget",
            "type": "budget_policy",
            "status": "pass",
            "source": "budgets/prod-support.json",
        },
        {
            "id": "tool-approval",
            "type": "tool_runtime",
            "status": "pass",
            "source": "test-vectors/tool-runtime/permissions.json",
        },
    ],
    evidence={
        "auditChain": {"recordCount": 42, "verified": True},
        "runtimeEvents": {"eventCount": 168, "contentFree": True},
        "trustBundle": {"present": True, "verified": True},
    },
)

result = verify_platform_runtime_profile(profile)

print(json.dumps(profile["readiness"], indent=2))
print(f"valid={result.valid} hash={result.computed_hash}")
