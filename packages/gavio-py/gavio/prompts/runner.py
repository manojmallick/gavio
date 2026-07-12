"""File-backed eval runner and CI gates."""

from __future__ import annotations

import json
import platform
import sys
from dataclasses import dataclass
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any
from xml.etree import ElementTree as ET

from .registry import EvalReport, EvalSuite, PromptRegistry, PromptTemplate

SCHEMA_VERSION = "1.0"
_YAML_SUFFIXES = {".yaml", ".yml"}
_OUTPUT_KEYS = ("mockOutput", "mock_output", "output", "expectedOutput", "expected_output")


@dataclass(frozen=True)
class EvalGate:
    passed: bool
    reasons: tuple[str, ...]
    fail_under: float | None
    baseline_score: float | None
    max_regression: float
    score_delta: float | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "reasons": list(self.reasons),
            "failUnder": self.fail_under,
            "baselineScore": self.baseline_score,
            "maxRegression": self.max_regression,
            "scoreDelta": self.score_delta,
        }


@dataclass(frozen=True)
class EvalRunResult:
    report: EvalReport
    gate: EvalGate
    source: str

    @property
    def passed(self) -> bool:
        return self.gate.passed

    def to_dict(self) -> dict[str, Any]:
        data = self.report.to_dict()
        data.update(
            {
                "schemaVersion": SCHEMA_VERSION,
                "runner": {
                    "name": "gavio-eval-runner",
                    "version": _package_version(),
                    "python": platform.python_version(),
                },
                "source": self.source,
                "gate": self.gate.to_dict(),
            }
        )
        return data


def run_eval_file(
    suite_path: str | Path,
    *,
    template_paths: list[str | Path] | None = None,
    fail_under: float | None = None,
    baseline_path: str | Path | None = None,
    max_regression: float = 0.0,
) -> EvalRunResult:
    """Run one deterministic eval suite file.

    The runner is intentionally local and CI-friendly: every case must provide
    a deterministic output through a case-level output key or a top-level
    ``outputs`` map. Provider calls are left to application code that uses the
    in-process ``EvalSuite`` API directly.
    """

    suite_file = Path(suite_path).expanduser()
    payload = load_eval_document(suite_file)
    registry = PromptRegistry(_load_templates(suite_file, payload, template_paths or []))
    suite = EvalSuite.from_dict(_suite_payload(payload))
    outputs = _output_map(payload, suite)
    report = suite.run_sync(registry, lambda _prompt, case: outputs[case.id])
    baseline_score = _load_baseline_score(baseline_path) if baseline_path is not None else None
    gate = evaluate_gate(
        report,
        fail_under=fail_under,
        baseline_score=baseline_score,
        max_regression=max_regression,
    )
    return EvalRunResult(report=report, gate=gate, source=str(suite_file))


def evaluate_gate(
    report: EvalReport,
    *,
    fail_under: float | None = None,
    baseline_score: float | None = None,
    max_regression: float = 0.0,
) -> EvalGate:
    reasons: list[str] = []
    if report.failed_cases:
        reasons.append(f"{report.failed_cases} eval case(s) failed")
    if fail_under is not None and report.score < fail_under:
        reasons.append(f"score {report.score:.8f} is below fail-under {fail_under:.8f}")
    score_delta: float | None = None
    if baseline_score is not None:
        score_delta = round(report.score - baseline_score, 8)
        if score_delta < -max_regression:
            reasons.append(
                "score regression "
                f"{score_delta:.8f} exceeds allowed {max_regression:.8f}"
            )
    return EvalGate(
        passed=not reasons,
        reasons=tuple(reasons),
        fail_under=fail_under,
        baseline_score=baseline_score,
        max_regression=max_regression,
        score_delta=score_delta,
    )


def load_eval_document(path: str | Path) -> dict[str, Any]:
    """Load a JSON or YAML eval document."""

    document_path = Path(path).expanduser()
    text = document_path.read_text(encoding="utf-8")
    if document_path.suffix.lower() in _YAML_SUFFIXES:
        return _load_yaml(text, document_path)
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError(f"{document_path}: expected a JSON object")
    return data


def write_json_report(result: EvalRunResult, path: str | Path, *, pretty: bool = False) -> None:
    indent = 2 if pretty else None
    Path(path).expanduser().write_text(
        json.dumps(result.to_dict(), indent=indent, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def write_junit_report(result: EvalRunResult, path: str | Path) -> None:
    Path(path).expanduser().write_text(junit_xml(result), encoding="utf-8")


def junit_xml(result: EvalRunResult) -> str:
    report = result.report
    suite = ET.Element(
        "testsuite",
        {
            "name": report.suite_id,
            "tests": str(report.total_cases),
            "failures": str(report.failed_cases),
            "errors": "0",
            "skipped": "0",
        },
    )
    properties = ET.SubElement(suite, "properties")
    ET.SubElement(properties, "property", {"name": "gavio.score", "value": str(report.score)})
    ET.SubElement(
        properties,
        "property",
        {"name": "gavio.gate.passed", "value": str(result.passed).lower()},
    )
    for reason in result.gate.reasons:
        ET.SubElement(properties, "property", {"name": "gavio.gate.reason", "value": reason})

    for case in report.cases:
        testcase = ET.SubElement(
            suite,
            "testcase",
            {
                "classname": case.template_id,
                "name": case.id,
                "time": "0",
            },
        )
        case_props = ET.SubElement(testcase, "properties")
        ET.SubElement(
            case_props,
            "property",
            {"name": "gavio.template.version", "value": case.template_version},
        )
        ET.SubElement(case_props, "property", {"name": "gavio.score", "value": str(case.score)})
        ET.SubElement(
            case_props,
            "property",
            {"name": "gavio.output_hash", "value": case.output_hash},
        )
        if not case.passed:
            failed = [assertion for assertion in case.assertions if not assertion.passed]
            message = "; ".join(assertion.reason for assertion in failed) or "eval failed"
            failure = ET.SubElement(testcase, "failure", {"message": message})
            failure.text = message
    return ET.tostring(suite, encoding="unicode", xml_declaration=True) + "\n"


def _package_version() -> str:
    try:
        return version("gavio")
    except PackageNotFoundError:
        return "0+unknown"


def _suite_payload(payload: dict[str, Any]) -> dict[str, Any]:
    suite = payload.get("suite")
    if suite is not None:
        if not isinstance(suite, dict):
            raise ValueError("suite must be an object")
        return suite
    if "id" not in payload or "cases" not in payload:
        raise ValueError("eval document must contain either suite or id/cases")
    return {"id": payload["id"], "cases": payload["cases"]}


def _load_templates(
    suite_path: Path,
    payload: dict[str, Any],
    template_paths: list[str | Path],
) -> list[PromptTemplate | dict[str, Any]]:
    templates: list[PromptTemplate | dict[str, Any]] = []
    inline = payload.get("templates")
    if inline is not None:
        if not isinstance(inline, list):
            raise ValueError("templates must be a list")
        templates.extend(inline)

    paths: list[str | Path] = list(template_paths)
    for key in ("templatesFile", "templates_file", "templateFile", "template_file"):
        value = payload.get(key)
        if value is not None:
            paths.append(str(value))
    for key in ("templateFiles", "template_files"):
        value = payload.get(key)
        if isinstance(value, list):
            paths.extend(str(item) for item in value)

    for path in paths:
        template_payload = load_eval_document(_resolve_relative(suite_path, path))
        items = template_payload.get("templates", template_payload)
        if isinstance(items, dict):
            items = [items]
        if not isinstance(items, list):
            raise ValueError(f"{path}: expected template object/list or templates list")
        templates.extend(items)

    if not templates:
        raise ValueError("eval document must provide templates or template files")
    return templates


def _output_map(payload: dict[str, Any], suite: EvalSuite) -> dict[str, str]:
    outputs: dict[str, str] = {}
    raw_outputs = (
        payload.get("outputs") or payload.get("mockOutputs") or payload.get("mock_outputs")
    )
    if isinstance(raw_outputs, dict):
        outputs.update({str(key): str(value) for key, value in raw_outputs.items()})

    for raw_case in _suite_payload(payload).get("cases", []):
        if not isinstance(raw_case, dict):
            continue
        case_id = str(raw_case.get("id", ""))
        for key in _OUTPUT_KEYS:
            if key in raw_case:
                outputs[case_id] = str(raw_case[key])
                break

    missing = [case.id for case in suite.cases if case.id not in outputs]
    if missing:
        raise ValueError(
            "deterministic eval runner requires outputs for case(s): "
            + ", ".join(missing)
        )
    return outputs


def _load_baseline_score(path: str | Path) -> float:
    data = load_eval_document(path)
    score = data.get("score")
    if score is None and isinstance(data.get("report"), dict):
        score = data["report"].get("score")
    if score is None:
        raise ValueError(f"{path}: baseline report missing score")
    return float(score)


def _resolve_relative(base_file: Path, value: str | Path) -> Path:
    path = Path(value).expanduser()
    return path if path.is_absolute() else base_file.parent / path


def _load_yaml(text: str, path: Path) -> dict[str, Any]:
    try:
        import yaml  # type: ignore[import-untyped]
    except ModuleNotFoundError:
        return _load_simple_yaml(text, path)
    data = yaml.safe_load(text)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a YAML object")
    return data


def _load_simple_yaml(text: str, path: Path) -> dict[str, Any]:
    """Parse the small YAML subset used by eval suite files.

    This fallback supports nested mappings, lists, and scalar values. It is not
    a general YAML implementation; install ``gavio[yaml]``/PyYAML for the full
    YAML surface.
    """

    lines = [
        (len(raw) - len(raw.lstrip(" ")), raw.strip())
        for raw in text.splitlines()
        if raw.strip() and not raw.lstrip().startswith("#")
    ]
    if not lines:
        raise ValueError(f"{path}: empty YAML document")
    value, index = _parse_yaml_block(lines, 0, lines[0][0], path)
    if index != len(lines):
        raise ValueError(f"{path}: could not parse YAML near: {lines[index][1]}")
    if not isinstance(value, dict):
        raise ValueError(f"{path}: expected a YAML object")
    return value


def _parse_yaml_block(
    lines: list[tuple[int, str]],
    index: int,
    indent: int,
    path: Path,
) -> tuple[Any, int]:
    if index >= len(lines):
        return {}, index
    actual_indent, text = lines[index]
    if actual_indent < indent:
        return {}, index
    if actual_indent != indent:
        raise ValueError(f"{path}: unexpected indentation near: {text}")
    if text.startswith("- "):
        return _parse_yaml_list(lines, index, indent, path)
    return _parse_yaml_map(lines, index, indent, path)


def _parse_yaml_map(
    lines: list[tuple[int, str]],
    index: int,
    indent: int,
    path: Path,
) -> tuple[dict[str, Any], int]:
    out: dict[str, Any] = {}
    while index < len(lines):
        line_indent, text = lines[index]
        if line_indent < indent:
            break
        if line_indent != indent:
            raise ValueError(f"{path}: unexpected indentation near: {text}")
        if text.startswith("- "):
            break
        key, raw_value = _split_yaml_key_value(text, path)
        index += 1
        if raw_value == "":
            if index < len(lines) and lines[index][0] > indent:
                value, index = _parse_yaml_block(lines, index, lines[index][0], path)
            else:
                value = {}
        else:
            value = _parse_yaml_scalar(raw_value)
        out[key] = value
    return out, index


def _parse_yaml_list(
    lines: list[tuple[int, str]],
    index: int,
    indent: int,
    path: Path,
) -> tuple[list[Any], int]:
    out: list[Any] = []
    while index < len(lines):
        line_indent, text = lines[index]
        if line_indent < indent:
            break
        if line_indent != indent or not text.startswith("- "):
            break
        item_text = text[2:].strip()
        index += 1
        if item_text == "":
            if index < len(lines) and lines[index][0] > indent:
                item, index = _parse_yaml_block(lines, index, lines[index][0], path)
            else:
                item = {}
        elif _looks_like_yaml_key_value(item_text):
            key, raw_value = _split_yaml_key_value(item_text, path)
            item = {key: _parse_yaml_scalar(raw_value) if raw_value else {}}
            while index < len(lines) and lines[index][0] > indent:
                child_indent, child_text = lines[index]
                if child_text.startswith("- "):
                    break
                child_key, child_raw_value = _split_yaml_key_value(child_text, path)
                index += 1
                if child_raw_value == "":
                    if index < len(lines) and lines[index][0] > child_indent:
                        child_value, index = _parse_yaml_block(
                            lines, index, lines[index][0], path
                        )
                    else:
                        child_value = {}
                else:
                    child_value = _parse_yaml_scalar(child_raw_value)
                item[child_key] = child_value
        else:
            item = _parse_yaml_scalar(item_text)
        out.append(item)
    return out, index


def _looks_like_yaml_key_value(text: str) -> bool:
    return ":" in text and not text.startswith(("http://", "https://"))


def _split_yaml_key_value(text: str, path: Path) -> tuple[str, str]:
    if ":" not in text:
        raise ValueError(f"{path}: expected key: value near: {text}")
    key, value = text.split(":", 1)
    key = key.strip()
    if not key:
        raise ValueError(f"{path}: empty YAML key near: {text}")
    return key, value.strip()


def _parse_yaml_scalar(value: str) -> Any:
    if value in {"", "null", "Null", "NULL", "~"}:
        return None
    if value in {"true", "True", "TRUE"}:
        return True
    if value in {"false", "False", "FALSE"}:
        return False
    if (value.startswith('"') and value.endswith('"')) or (
        value.startswith("'") and value.endswith("'")
    ):
        return value[1:-1]
    try:
        return int(value)
    except ValueError:
        pass
    try:
        return float(value)
    except ValueError:
        return value


def cli_summary(result: EvalRunResult) -> dict[str, Any]:
    return {
        "suiteId": result.report.suite_id,
        "score": result.report.score,
        "passedCases": result.report.passed_cases,
        "failedCases": result.report.failed_cases,
        "gate": result.gate.to_dict(),
    }


def print_json(data: dict[str, Any], *, pretty: bool = False) -> None:
    indent = 2 if pretty else None
    print(json.dumps(data, indent=indent, sort_keys=True))


def exit_code(result: EvalRunResult) -> int:
    return 0 if result.passed else 1


def error_exit(prefix: str, error: Exception) -> int:
    print(f"{prefix}: {error}", file=sys.stderr)
    return 1
