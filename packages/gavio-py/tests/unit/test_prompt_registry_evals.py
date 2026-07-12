from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio.prompts import EvalSuite, PromptRegistry, PromptTemplate

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors" / "prompts"


def _load() -> dict:
    return json.loads((_VECTORS / "registry-evals.json").read_text(encoding="utf-8"))


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
