"""CLI error handling: --since parsing, unknown cost dimensions, unsafe inspector bind."""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from gavio.cli import main as cli_main
from gavio.inspector.analytics import _parse_since, build_cost_report


def _summary(minutes_ago: float) -> dict:
    when = datetime.now(timezone.utc) - timedelta(minutes=minutes_ago)
    return {
        "traceId": f"t{minutes_ago}",
        "wallTimeUtc": when.isoformat(),
        "agentId": "demo",
        "costUsd": 0.01,
        "latencyMs": 5,
        "status": "ok",
    }


@pytest.fixture
def audit(tmp_path: Path) -> Path:
    path = tmp_path / "audit.jsonl"
    path.write_text("\n".join(json.dumps(_summary(m)) for m in (5, 120)) + "\n", encoding="utf-8")
    return path


def test_since_accepts_zone_less_and_z_timestamps() -> None:
    assert _parse_since("2026-10-01T00:00:00").tzinfo is not None
    assert _parse_since("2026-10-01T00:00:00Z") == datetime(2026, 10, 1, tzinfo=timezone.utc)
    assert _parse_since("2026-10-01T02:00:00+02:00") == datetime(2026, 10, 1, tzinfo=timezone.utc)


@pytest.mark.parametrize(
    ("text", "seconds"), [("30m", 1800), ("1h", 3600), ("7d", 604800), ("2w", 1209600)]
)
def test_since_accepts_relative_durations(text: str, seconds: int) -> None:
    delta = datetime.now(timezone.utc) - _parse_since(text)
    assert abs(delta.total_seconds() - seconds) < 5


@pytest.mark.parametrize("bad", ["bogus", "", "1x", "-1h", "yesterday"])
def test_since_rejects_garbage_with_a_clear_message(bad: str) -> None:
    with pytest.raises(ValueError, match="ISO 8601 timestamp"):
        _parse_since(bad)


def test_relative_since_filters_records() -> None:
    summaries = [_summary(5), _summary(120)]
    assert build_cost_report(summaries, since="1h")["total"]["requests"] == 1
    assert build_cost_report(summaries, since="3h")["total"]["requests"] == 2


def test_cost_report_cli_since_without_zone_does_not_crash(
    audit: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    rc = cli_main(["cost", "report", "--audit", str(audit), "--since", "2000-01-01T00:00:00"])
    assert rc == 0
    assert json.loads(capsys.readouterr().out)["total"]["requests"] == 2


def test_cost_report_cli_bad_since_is_one_line_error(
    audit: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    rc = cli_main(["cost", "report", "--audit", str(audit), "--since", "soon"])
    err = capsys.readouterr().err
    assert rc == 1
    assert err.startswith("gavio cost report: since must be")
    assert "Traceback" not in err


def test_cost_report_warns_when_dimension_is_missing(
    audit: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    rc = cli_main(["cost", "report", "--audit", str(audit), "--group-by", "tenant"])
    captured = capsys.readouterr()
    assert rc == 0
    assert "warning" in captured.err and "'tenant'" in captured.err
    assert list(json.loads(captured.out)["groups"]) == ["unknown"]


def test_cost_report_does_not_warn_for_a_present_dimension(
    audit: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    rc = cli_main(["cost", "report", "--audit", str(audit), "--group-by", "agent_id"])
    captured = capsys.readouterr()
    assert rc == 0
    assert captured.err == ""
    assert list(json.loads(captured.out)["groups"]) == ["demo"]


def test_inspect_non_loopback_bind_without_token_is_one_line_error(
    audit: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    rc = cli_main(["inspect", "--store", str(audit), "--bind", "0.0.0.0", "--port", "0"])
    err = capsys.readouterr().err
    assert rc == 1
    assert err.startswith("gavio inspect: inspector bind '0.0.0.0' is not loopback")
    assert "Traceback" not in err
