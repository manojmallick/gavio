"""Tool Runtime tests (F-TOOL-01/02/03/04)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio import Gateway, ToolRuntimeError
from gavio.context import InterceptorContext
from gavio.interceptors.base import Interceptor
from gavio.interceptors.tool_runtime import ToolRuntimeInterceptor, analyze_tool_runtime


def _vectors_file() -> Path:
    directory = Path.cwd().resolve()
    while directory != directory.parent:
        candidate = directory / "test-vectors" / "tool-runtime" / "cases.json"
        if candidate.is_file():
            return candidate
        directory = directory.parent
    raise AssertionError("could not locate tool-runtime vectors")


def _cases() -> list[dict]:
    return json.loads(_vectors_file().read_text())["cases"]


@pytest.mark.parametrize("case", _cases(), ids=lambda c: c["id"])
def test_tool_runtime_shared_vectors(case):
    decision = analyze_tool_runtime(case["tools"])
    expected = case["expected"]

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
