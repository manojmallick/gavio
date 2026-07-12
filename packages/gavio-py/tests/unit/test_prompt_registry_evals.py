from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio.prompts import (
    EvalSuite,
    PromptRegistry,
    PromptTemplate,
    build_prompt_release_bundle,
    diff_prompt_templates,
    evaluate_prompt_workflow,
    prompt_eval_links_from_manifest,
    sign_prompt_manifest,
    verify_prompt_manifest_signature,
)

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors" / "prompts"


def _load() -> dict:
    return json.loads((_VECTORS / "registry-evals.json").read_text(encoding="utf-8"))


def _load_v2() -> dict:
    return json.loads((_VECTORS / "registry-v2.json").read_text(encoding="utf-8"))


def _load_workflow() -> dict:
    return json.loads((_VECTORS / "workflow.json").read_text(encoding="utf-8"))


def _assert_workflow_content_safe(serialized: str, vector: dict) -> None:
    for content in vector["contentKeys"]:
        if content in {"output", "renderedPrompt"}:
            assert f'"{content}"' not in serialized
        else:
            assert content not in serialized


def test_prompt_registry_renders_shared_vectors_without_raw_lineage_content() -> None:
    vectors = _load()
    template_vector = vectors["templates"][0]
    registry = PromptRegistry([PromptTemplate.from_dict(template_vector)])

    rendered = registry.render(template_vector["id"], template_vector["variables"])

    assert rendered.messages == template_vector["expectedMessages"]
    assert rendered.lineage.template_id == template_vector["expectedLineage"]["templateId"]
    assert (
        rendered.lineage.template_version
        == template_vector["expectedLineage"]["templateVersion"]
    )
    assert rendered.lineage.variables == template_vector["expectedLineage"]["variables"]
    assert rendered.lineage.rag_chunks == []
    assert "renderedPrompt" not in rendered.lineage.to_dict()


def test_prompt_registry_reports_missing_required_variables() -> None:
    template_vector = _load()["templates"][0]
    registry = PromptRegistry([template_vector])
    variables = {
        key: value
        for key, value in template_vector["variables"].items()
        if key not in template_vector["missingVariables"]
    }

    with pytest.raises(ValueError) as error:
        registry.render(template_vector["id"], variables)

    assert "topic" in str(error.value)


@pytest.mark.asyncio
async def test_eval_suite_runs_shared_vectors_without_raw_outputs() -> None:
    vectors = _load()
    registry = PromptRegistry(vectors["templates"])
    suite = EvalSuite.from_dict(vectors["suite"])
    outputs = {case["id"]: case["mockOutput"] for case in vectors["suite"]["cases"]}

    report = await suite.run(registry, lambda _prompt, case: outputs[case.id])
    data = report.to_dict()

    assert data["suiteId"] == vectors["suite"]["expectedReport"]["suiteId"]
    assert data["totalCases"] == vectors["suite"]["expectedReport"]["totalCases"]
    assert data["passedCases"] == vectors["suite"]["expectedReport"]["passedCases"]
    assert data["failedCases"] == vectors["suite"]["expectedReport"]["failedCases"]
    assert data["score"] == vectors["suite"]["expectedReport"]["score"]
    for case_result, case_vector in zip(data["cases"], vectors["suite"]["cases"], strict=True):
        assert case_result["passed"] == case_vector["expected"]["passed"]
        assert case_result["score"] == case_vector["expected"]["score"]
        assert len(case_result["outputHash"]) == 64

    serialized = json.dumps(data)
    for content_key in vectors["contentKeys"]:
        assert f'"{content_key}"' not in serialized
    for case in vectors["suite"]["cases"]:
        assert case["mockOutput"] not in serialized


def test_prompt_registry_v2_loads_signed_file_and_resolves_semver_ranges(tmp_path: Path) -> None:
    vector = _load_v2()
    manifest = vector["manifest"]
    secret = vector["signatureSecret"]
    manifest_path = tmp_path / "prompts.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    assert verify_prompt_manifest_signature(manifest, secret)

    registry = PromptRegistry.from_file(manifest_path, verify_secret=secret)

    latest = registry.get("support.reply")
    assert latest.version == vector["expected"]["latestVersion"]
    assert registry.get("support.reply", "^1.0.0").version == vector["expected"]["caretVersion"]
    assert registry.get("support.reply", "~1.1.0").version == vector["expected"]["tildeVersion"]
    assert (
        registry.get("support.reply", ">=1.0.0 <2.0.0").version
        == vector["expected"]["rangeVersion"]
    )
    assert latest.approval is not None
    assert latest.approval.status == "pending"

    rendered = registry.render(
        "support.reply",
        {"customerName": "Avery", "topic": "refund status", "orderId": "A-100"},
    )
    assert rendered.messages[-1]["content"] == vector["expected"]["renderedMessage"]

    signed_round_trip = registry.to_manifest(
        registry_id=vector["registryId"],
        metadata=vector["metadata"],
        sign_secret=secret,
        key_id=manifest["signature"]["keyId"],
    )
    assert signed_round_trip["signature"]["value"] == manifest["signature"]["value"]


def test_prompt_registry_v2_diff_is_metadata_safe() -> None:
    vector = _load_v2()
    templates = [PromptTemplate.from_dict(raw) for raw in vector["manifest"]["templates"]]

    diff = diff_prompt_templates(templates[0], templates[1])
    data = diff.to_dict()

    assert [change["path"] for change in data["changes"]] == vector["expected"]["diffPaths"]
    assert all(
        "beforeHash" in change or "afterHash" in change
        for change in data["changes"]
        if str(change["path"]).startswith("messages[")
    )
    serialized = json.dumps(data)
    for content in vector["expected"]["contentKeys"]:
        assert content not in serialized


def test_prompt_registry_v2_rejects_bad_semver_manifest() -> None:
    vector = _load_v2()
    manifest = dict(vector["manifest"])
    bad_template = dict(manifest["templates"][0])
    bad_template["version"] = "2026-07-12"
    manifest["templates"] = [bad_template]

    with pytest.raises(ValueError):
        PromptRegistry.from_manifest(manifest, validate_semver=True)


def test_prompt_registry_v2_signs_manifest_deterministically() -> None:
    vector = _load_v2()
    manifest = dict(vector["manifest"])
    unsigned = {key: value for key, value in manifest.items() if key != "signature"}

    signed = sign_prompt_manifest(
        unsigned,
        vector["signatureSecret"],
        key_id=manifest["signature"]["keyId"],
    )

    assert signed["signature"] == manifest["signature"]


@pytest.mark.asyncio
async def test_prompt_eval_workflow_gates_and_triage_metadata_are_safe() -> None:
    vector = _load_workflow()
    registry = PromptRegistry.from_manifest(vector["manifest"])
    suite = EvalSuite.from_dict(vector["suite"])
    outputs = {case["id"]: case["mockOutput"] for case in vector["suite"]["cases"]}

    report = await suite.run(registry, lambda _prompt, case: outputs[case.id])
    links = prompt_eval_links_from_manifest(vector["manifest"])
    workflow = evaluate_prompt_workflow(report, links)
    data = report.to_dict()

    assert workflow.passed is False
    gate = workflow.gates[0]
    assert gate.prompt_id == vector["expected"]["promptId"]
    assert gate.prompt_version == vector["expected"]["promptVersion"]
    assert gate.score == vector["expected"]["score"]
    assert gate.baseline_score == vector["expected"]["baselineScore"]
    assert gate.score_delta == vector["expected"]["scoreDelta"]
    assert list(gate.failed_cases) == vector["expected"]["failedCases"]
    failed = next(case for case in data["cases"] if case["id"] == "refund-leak")
    assert failed["triage"]["category"] == vector["expected"]["triage"]["category"]
    assert failed["triage"]["severity"] == vector["expected"]["triage"]["severity"]
    assert "outputHash" in failed["triage"]["metadata"]
    assert "output" not in failed["triage"]["metadata"]
    assert "triage" not in next(case for case in data["cases"] if case["id"] == "refund-safe")

    serialized = json.dumps({"report": data, "workflow": workflow.to_dict()})
    _assert_workflow_content_safe(serialized, vector)


@pytest.mark.asyncio
async def test_prompt_release_bundle_contains_prompt_diff_and_eval_evidence() -> None:
    vector = _load_workflow()
    registry = PromptRegistry.from_manifest(vector["manifest"])
    suite = EvalSuite.from_dict(vector["suite"])
    outputs = {case["id"]: case["mockOutput"] for case in vector["suite"]["cases"]}
    report = await suite.run(registry, lambda _prompt, case: outputs[case.id])

    bundle = build_prompt_release_bundle(
        manifest=vector["manifest"],
        prompt_id=vector["expected"]["promptId"],
        prompt_version=vector["expected"]["promptVersion"],
        from_version=vector["expected"]["fromVersion"],
        reports=[report],
        generated_at="2026-07-12T12:00:00Z",
    )
    data = bundle.to_dict()

    assert data["schemaVersion"] == "gavio.prompt-release-bundle.v1"
    assert data["prompt"] == {
        "id": vector["expected"]["promptId"],
        "version": vector["expected"]["promptVersion"],
    }
    assert len(data["manifest"]["digest"]) == 64
    assert data["passed"] is False
    assert data["gates"][0]["failedCases"] == vector["expected"]["failedCases"]
    assert data["promptDiff"]["hasChanges"] is True
    serialized = json.dumps(data)
    _assert_workflow_content_safe(serialized, vector)
