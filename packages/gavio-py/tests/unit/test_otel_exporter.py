from __future__ import annotations

import io
import json
from pathlib import Path

from gavio.cli import main as cli_main
from gavio.exporters import OtelSpanExporter, otel_spans_from_events

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


def _load_vector() -> dict:
    return json.loads((_VECTORS / "otel" / "spans.json").read_text())


def test_otel_span_mapper_matches_shared_vectors() -> None:
    vector = _load_vector()
    for case in vector["cases"]:
        spans = otel_spans_from_events(case["events"], service_name=case["serviceName"])
        _assert_case(case, spans)
        serialized = json.dumps(spans)
        for key in vector["contentKeys"]:
            assert f'"{key}"' not in serialized


def test_otel_exporter_writes_jsonl() -> None:
    case = _load_vector()["cases"][0]
    stream = io.StringIO()
    exporter = OtelSpanExporter(stream=stream, service_name=case["serviceName"])

    for event in case["events"]:
        exporter.export_event(event)
    exporter.flush()

    spans = [json.loads(line) for line in stream.getvalue().splitlines()]
    _assert_case(case, spans)


def test_cli_converts_runtime_events_to_otel_jsonl(tmp_path: Path, capsys) -> None:
    case = _load_vector()["cases"][1]
    events_path = tmp_path / "events.jsonl"
    events_path.write_text("\n".join(json.dumps(event) for event in case["events"]))

    result = cli_main(
        [
            "events",
            "convert",
            "--from",
            str(events_path),
            "--to",
            "otel-json",
            "--service-name",
            case["serviceName"],
        ]
    )

    assert result == 0
    spans = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    _assert_case(case, spans)


def _assert_case(case: dict, spans: list[dict]) -> None:
    expected = case["expected"]
    assert [span["name"] for span in spans] == expected["spanNames"]

    root = _span(spans, expected["root"]["name"])
    assert root["parentSpanId"] is None
    assert root["status"]["code"] == expected["root"]["status"]
    assert root["endTimeUnixNano"] - root["startTimeUnixNano"] == expected["root"]["durationNs"]
    _assert_attrs(root, expected["root"]["attributes"])
    if "eventNames" in expected["root"]:
        assert [event["name"] for event in root["events"]] == expected["root"]["eventNames"]

    for section in ("provider", "interceptor"):
        if section not in expected:
            continue
        span = _span(spans, expected[section]["name"])
        assert span["parentSpanId"] == root["spanId"]
        assert span["status"]["code"] == expected[section]["status"]
        start_offset = span["startTimeUnixNano"] - root["startTimeUnixNano"]
        end_offset = span["endTimeUnixNano"] - root["startTimeUnixNano"]
        assert start_offset == expected[section]["startOffsetNs"]
        assert end_offset == expected[section]["endOffsetNs"]
        _assert_attrs(span, expected[section]["attributes"])


def _span(spans: list[dict], name: str) -> dict:
    return next(span for span in spans if span["name"] == name)


def _assert_attrs(span: dict, expected: dict) -> None:
    for key, value in expected.items():
        assert span["attributes"].get(key) == value
