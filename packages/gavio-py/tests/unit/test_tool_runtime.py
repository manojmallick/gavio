"""Tool Runtime tests (F-TOOL-01/02/03/04)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio import Gateway, ToolRuntimeError
from gavio.context import InterceptorContext
from gavio.interceptors.base import Interceptor
from gavio.interceptors.tool_runtime import (
    ToolRuntimeInterceptor,
    analyze_tool_runtime,
    replay_tool_runtime,
)


def _vectors_file(name: str = "cases.json") -> Path:
    directory = Path.cwd().resolve()
    while directory != directory.parent:
        candidate = directory / "test-vectors" / "tool-runtime" / name
        if candidate.is_file():
            return candidate
        directory = directory.parent
    raise AssertionError(f"could not locate tool-runtime vector {name}")


def _cases(name: str = "cases.json") -> list[dict]:
    return json.loads(_vectors_file(name).read_text())["cases"]


def _assert_decision(decision: dict, expected: dict) -> None:
    assert len(decision["violations"]) == expected["violation_count"]
    assert len(decision["conflicts"]) == expected.get("conflict_count", 0)
    if "confidence" in expected:
        assert decision["confidence"] == pytest.approx(expected["confidence"])
    if "provenance_count" in expected:
        assert len(decision["provenance"]) == expected["provenance_count"]
    if "first_violation_kind" in expected:
        assert decision["violations"][0]["kind"] == expected["first_violation_kind"]
    if "first_conflict_key" in expected:
        assert decision["conflicts"][0]["key"] == expected["first_conflict_key"]
    if "decision_count" in expected:
        assert len(decision["decisions"]) == expected["decision_count"]
    if "first_action" in expected:
        assert decision["decisions"][0]["action"] == expected["first_action"]
    if "first_approved" in expected:
        assert decision["decisions"][0]["approved"] is expected["first_approved"]
    if "approval_required_count" in expected:
        assert decision["approvals_required"] == expected["approval_required_count"]
    if "blocked_count" in expected:
        assert decision["blocked"] == expected["blocked_count"]
    if "first_mcp_server" in expected:
        assert decision["provenance"][0]["mcp_server"] == expected["first_mcp_server"]
    if "replayable" in expected:
        assert decision["replayable"] is expected["replayable"]


@pytest.mark.parametrize("case", _cases(), ids=lambda c: c["id"])
def test_tool_runtime_shared_vectors(case):
    decision = analyze_tool_runtime(case["tools"])
    _assert_decision(decision, case["expected"])


@pytest.mark.parametrize("case", _cases("permissions.json"), ids=lambda c: c["id"])
def test_tool_runtime_permission_vectors(case):
    decision = analyze_tool_runtime(case["tools"])
    _assert_decision(decision, case["expected"])


@pytest.mark.parametrize("case", _cases("replay.json"), ids=lambda c: c["id"])
def test_tool_runtime_replay_vectors(case):
    decision = replay_tool_runtime(case["record"])
    _assert_decision(decision, case["expected"])


class RuntimeCapture(Interceptor):
    def __init__(self) -> None:
        self.ctx: InterceptorContext | None = None

    @property
    def name(self) -> str:
        return "tool_runtime_capture"

    async def before(self, request, ctx):
        self.ctx = ctx
        return request


async def test_tool_runtime_records_context_without_mutating_request_metadata():
    tools = _cases()[0]["tools"]
    capture = RuntimeCapture()
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(ToolRuntimeInterceptor())
        .use(capture)
        .build()
    )

    await gw.complete(messages=[{"role": "user", "content": "hi"}], metadata={"tools": tools})

    assert "runtime" not in tools
    assert capture.ctx is not None
    runtime = capture.ctx.tools["runtime"]
    assert runtime["call_count"] == 1
    assert runtime["violations"] == []
    assert runtime["provenance"][0]["source"] == "warehouse-a"


async def test_tool_runtime_blocks_when_configured_for_error():
    tools = _cases()[1]["tools"]
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(ToolRuntimeInterceptor(on_failure="error"))
        .build()
    )

    with pytest.raises(ToolRuntimeError):
        await gw.complete(messages=[{"role": "user", "content": "hi"}], metadata={"tools": tools})
