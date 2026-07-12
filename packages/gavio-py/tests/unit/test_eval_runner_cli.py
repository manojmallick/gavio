from __future__ import annotations

import json
from pathlib import Path
from xml.etree import ElementTree as ET

import pytest

from gavio.cli import main as cli_main


def _template() -> dict:
    return {
        "id": "support.reply",
        "version": "2026-07-12",
        "messages": [
            {"role": "system", "content": "You are concise."},
            {"role": "user", "content": "Reply to {{ customer }} about {{ topic }}."},
        ],
        "requiredVariables": ["customer", "topic"],
    }


def _workflow_template() -> dict:
    template = _template()
    template["version"] = "1.1.0"
    template["metadata"] = {
        "promptEvalLinks": [
            {
                "suiteId": "support-ci",
                "baselineScore": 1.0,
                "failUnder": 0.95,
                "maxRegression": 0.05,
                "metadata": {"output": "raw metadata output"},
            }
        ]
    }
    return template


def _suite(output: str = "Avery refund approved") -> dict:
    return {
        "id": "support-ci",
        "cases": [
            {
                "id": "refund",
                "templateId": "support.reply",
                "templateVersion": "2026-07-12",
                "variables": {"customer": "Avery", "topic": "refund"},
                "mockOutput": output,
                "assertions": [
                    {"type": "contains", "value": "refund"},
                    {"type": "not_contains", "value": "card number"},
                ],
            }
        ],
    }


def _workflow_suite() -> dict:
    return {
        "id": "support-ci",
        "templates": [_workflow_template()],
        "cases": [
            {
                "id": "refund-leak",
                "templateId": "support.reply",
                "templateVersion": "1.1.0",
                "variables": {"customer": "Avery", "topic": "refund"},
                "mockOutput": "Avery, send your card number for refund.",
                "assertions": [
                    {"type": "contains", "value": "refund"},
                    {"type": "not_contains", "value": "card number"},
                ],
                "triage": {
                    "category": "safety",
                    "severity": "high",
                    "owner": "support-quality",
                    "action": "revise_prompt",
                    "metadata": {"output": "Avery, send your card number for refund."},
                },
            }
        ],
    }


def test_eval_run_cli_writes_json_and_junit_reports(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    templates = tmp_path / "templates.json"
    templates.write_text(json.dumps({"templates": [_template()]}), encoding="utf-8")
    suite = tmp_path / "suite.json"
    suite.write_text(json.dumps(_suite()), encoding="utf-8")
    report_path = tmp_path / "eval-report.json"
    junit_path = tmp_path / "eval-junit.xml"

    rc = cli_main([
        "eval",
        "run",
        str(suite),
        "--templates",
        str(templates),
        "--fail-under",
        "1.0",
        "--report",
        str(report_path),
        "--junit",
        str(junit_path),
    ])

    captured = capsys.readouterr()
    assert rc == 0
    stdout_report = json.loads(captured.out)
    file_report = json.loads(report_path.read_text(encoding="utf-8"))
    assert stdout_report["score"] == 1.0
    assert file_report["gate"]["passed"] is True
    assert len(file_report["cases"][0]["outputHash"]) == 64
    assert "Avery refund approved" not in captured.out
    assert "Avery refund approved" not in report_path.read_text(encoding="utf-8")

    junit = ET.parse(junit_path).getroot()
    assert junit.tag == "testsuite"
    assert junit.attrib["tests"] == "1"
    assert junit.attrib["failures"] == "0"


def test_eval_run_cli_reports_prompt_workflow_and_triage(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    suite = tmp_path / "suite.json"
    suite.write_text(json.dumps(_workflow_suite()), encoding="utf-8")
    report_path = tmp_path / "eval-report.json"
    junit_path = tmp_path / "eval-junit.xml"

    rc = cli_main([
        "eval",
        "run",
        str(suite),
        "--report",
        str(report_path),
        "--junit",
        str(junit_path),
        "--summary",
    ])

    summary = json.loads(capsys.readouterr().out)
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert rc == 1
    assert summary["workflow"]["passed"] is False
    assert summary["workflow"]["gates"][0]["failedCases"] == ["refund-leak"]
    assert report["cases"][0]["triage"]["category"] == "safety"
    assert "outputHash" in report["cases"][0]["triage"]["metadata"]
    assert "output" not in report["cases"][0]["triage"]["metadata"]
    serialized = json.dumps(report)
    assert "Avery, send your card number for refund." not in serialized
    assert "raw metadata output" not in serialized

    junit_text = junit_path.read_text(encoding="utf-8")
    assert "gavio.workflow.passed" in junit_text
    assert "gavio.prompt.gate" in junit_text
    assert "gavio.triage.category" in junit_text
    assert "Avery, send your card number for refund." not in junit_text


def test_eval_run_cli_returns_nonzero_for_threshold_failure(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    suite = tmp_path / "suite.json"
    suite.write_text(
        json.dumps({**_suite("refund card number"), "templates": [_template()]}),
        encoding="utf-8",
    )

    rc = cli_main(["eval", "run", str(suite), "--fail-under", "1.0", "--summary"])

    assert rc == 1
    summary = json.loads(capsys.readouterr().out)
    assert summary["score"] == 0.5
    assert summary["failedCases"] == 1
    assert any("fail-under" in reason for reason in summary["gate"]["reasons"])


def test_eval_run_cli_compares_against_baseline(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    suite = tmp_path / "suite.json"
    suite.write_text(json.dumps({**_suite("refund card number"), "templates": [_template()]}))
    baseline = tmp_path / "baseline.json"
    baseline.write_text(json.dumps({"score": 1.0}), encoding="utf-8")

    rc = cli_main([
        "eval",
        "run",
        str(suite),
        "--baseline",
        str(baseline),
        "--max-regression",
        "0.1",
        "--summary",
    ])

    assert rc == 1
    summary = json.loads(capsys.readouterr().out)
    assert summary["gate"]["baselineScore"] == 1.0
    assert summary["gate"]["scoreDelta"] == -0.5
    assert any("score regression" in reason for reason in summary["gate"]["reasons"])


def test_eval_run_cli_loads_yaml_suite(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    suite = tmp_path / "suite.yaml"
    suite.write_text(
        """
id: support-yaml
templates:
  - id: support.reply
    version: "2026-07-12"
    messages:
      - role: system
        content: You are concise.
      - role: user
        content: Reply to {{ customer }} about {{ topic }}.
    requiredVariables:
      - customer
      - topic
cases:
  - id: refund
    templateId: support.reply
    templateVersion: "2026-07-12"
    variables:
      customer: Avery
      topic: refund
    output: Avery refund approved
    assertions:
      - type: contains
        value: refund
""".strip(),
        encoding="utf-8",
    )

    rc = cli_main(["eval", "run", str(suite), "--fail-under", "1.0", "--summary"])

    assert rc == 0
    summary = json.loads(capsys.readouterr().out)
    assert summary["suiteId"] == "support-yaml"
    assert summary["score"] == 1.0
