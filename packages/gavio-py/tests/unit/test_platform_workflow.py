from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio import (
    build_platform_runtime_profile,
    build_production_trust_bundle,
    platform_workflow_release_hash,
    run_platform_workflow_release_file,
)
from gavio.cli import main as cli_main
from gavio.interceptors.pii import load_policy_pack


def _prompt_manifest() -> dict:
    return {
        "schemaVersion": "gavio.prompt-registry.v2",
        "registryId": "support-prompts",
        "templates": [
            {
                "id": "support.reply",
                "version": "1.0.0",
                "messages": [
                    {"role": "system", "content": "You are concise."},
                    {"role": "user", "content": "Write a reply about {{ topic }}."},
                ],
                "requiredVariables": ["topic"],
            },
            {
                "id": "support.reply",
                "version": "1.1.0",
                "messages": [
                    {"role": "system", "content": "You are concise and policy aware."},
                    {
                        "role": "user",
                        "content": "Write a reply to {{ customer }} about {{ topic }}.",
                    },
                ],
                "requiredVariables": ["customer", "topic"],
                "metadata": {
                    "promptEvalLinks": [
                        {
                            "suiteId": "support-release",
                            "baselineScore": 1.0,
                            "failUnder": 1.0,
                            "maxRegression": 0.0,
                            "metadata": {"output": "raw metadata output"},
                        }
                    ]
                },
            },
        ],
    }


def _suite(output: str = "Avery refund status approved") -> dict:
    return {
        "id": "support-release",
        "cases": [
            {
                "id": "refund-safe",
                "templateId": "support.reply",
                "templateVersion": "1.1.0",
                "variables": {"customer": "Avery", "topic": "refund status"},
                "mockOutput": output,
                "assertions": [
                    {"type": "contains", "value": "refund status"},
                    {"type": "not_contains", "value": "card number"},
                ],
                "triage": {
                    "category": "safety",
                    "severity": "high",
                    "owner": "support-quality",
                    "action": "revise_prompt",
                    "metadata": {"output": output},
                },
            }
        ],
    }


def _write_workflow_files(tmp_path: Path, *, output: str = "Avery refund status approved") -> Path:
    prompt_path = tmp_path / "prompts.json"
    suite_path = tmp_path / "suite.json"
    trust_path = tmp_path / "trust.json"
    profile_path = tmp_path / "profile.json"
    policy_path = tmp_path / "policy-pack"
    manifest_path = tmp_path / "workflow.json"

    prompt_path.write_text(json.dumps(_prompt_manifest()), encoding="utf-8")
    suite_path.write_text(json.dumps(_suite(output)), encoding="utf-8")
    policy_path.mkdir()
    (policy_path / "manifest.json").write_text(
        json.dumps(load_policy_pack("finance").manifest()),
        encoding="utf-8",
    )

    trust = build_production_trust_bundle(
        bundle_id="trust-platform-workflow",
        generated_at="2026-07-12T12:00:00Z",
        release={"version": "3.0.0", "tag": "v3.0.0", "commit": "abc1234"},
        runtime={"environment": "production", "policySource": "project:prod-support"},
        audit_chain_verified=True,
        runtime_events=[],
        controls=[{"id": "release-gate", "type": "release_gate", "status": "pass"}],
    )
    trust_path.write_text(json.dumps(trust), encoding="utf-8")

    profile = build_platform_runtime_profile(
        profile_id="platform-prod-support",
        generated_at="2026-07-12T12:00:00Z",
        sdk={"name": "gavio", "version": "3.0.0"},
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
    profile_path.write_text(json.dumps(profile), encoding="utf-8")

    manifest = {
        "schemaVersion": "gavio.platform-workflow.v1",
        "workflowId": "support-platform-release",
        "generatedAt": "2026-07-12T12:00:00Z",
        "release": {"version": "3.0.0", "tag": "v3.0.0", "commit": "abc1234"},
        "prompts": {
            "manifest": prompt_path.name,
            "promptId": "support.reply",
            "promptVersion": "1.1.0",
            "fromVersion": "1.0.0",
        },
        "evals": [{"suite": suite_path.name, "failUnder": 1.0}],
        "policies": [{"id": "finance-policy", "pathOrName": policy_path.name}],
        "trustBundle": {"path": trust_path.name},
        "runtimeProfile": {"path": profile_path.name},
        "metadata": {
            "owner": "platform",
            "content": "raw release notes must not persist",
        },
    }
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return manifest_path


def test_platform_workflow_release_builds_metadata_safe_artifact(tmp_path: Path) -> None:
    manifest_path = _write_workflow_files(tmp_path)

    result = run_platform_workflow_release_file(manifest_path)

    assert result.passed
    artifact = result.artifact
    assert artifact["schemaVersion"] == "gavio.platform-workflow-release.v1"
    assert artifact["workflowId"] == "support-platform-release"
    assert artifact["workflowHash"] == platform_workflow_release_hash(artifact)
    assert artifact["prompts"]["releaseBundles"][0]["passed"] is True
    assert artifact["policies"][0]["id"] == "finance-policy"
    assert artifact["policies"][0]["signatureValid"] is True
    assert artifact["trust"]["valid"] is True
    assert artifact["runtimeProfile"]["readiness"]["ready"] is True
    assert "contentHash" in artifact["metadata"]

    serialized = json.dumps(artifact)
    assert "Write a reply to {{ customer }} about {{ topic }}" not in serialized
    assert "Avery refund status approved" not in serialized
    assert "raw release notes must not persist" not in serialized
    assert "raw metadata output" not in serialized


def test_platform_workflow_release_cli_writes_output(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    manifest_path = _write_workflow_files(tmp_path)
    output_path = tmp_path / "release.json"

    rc = cli_main([
        "workflow",
        "release",
        str(manifest_path),
        "--output",
        str(output_path),
        "--pretty",
    ])

    stdout_artifact = json.loads(capsys.readouterr().out)
    file_artifact = json.loads(output_path.read_text(encoding="utf-8"))
    assert rc == 0
    assert stdout_artifact["workflowHash"] == file_artifact["workflowHash"]
    assert file_artifact["passed"] is True


def test_platform_workflow_release_cli_fails_closed_unless_allowed(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    manifest_path = _write_workflow_files(tmp_path, output="Avery needs a card number")

    rc = cli_main(["workflow", "release", str(manifest_path)])

    artifact = json.loads(capsys.readouterr().out)
    assert rc == 1
    assert artifact["passed"] is False
    assert any("eval:" in reason or "prompt:" in reason for reason in artifact["reasons"])
    assert "Avery needs a card number" not in json.dumps(artifact)

    allowed = cli_main(["workflow", "release", str(manifest_path), "--allow-failures"])
    assert allowed == 0
