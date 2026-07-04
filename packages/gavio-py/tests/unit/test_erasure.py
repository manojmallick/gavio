"""Tests for right-to-erasure — subject_id + purge (F-QUA-09, GDPR Art. 17)."""

from __future__ import annotations

import json

from gavio import Gateway
from gavio.interceptors.audit import AuditRecord, JsonlSink, StdoutSink
from gavio.interceptors.audit.interceptor import AuditInterceptor
from gavio.interceptors.audit.sink import AuditSink
from gavio.providers.mock import MockProvider


class CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


def _rec(subject_id: str | None, trace: str = "t") -> AuditRecord:
    return AuditRecord(
        trace_id=trace,
        provider="mock",
        model="m",
        timestamp_utc="now",
        subject_id=subject_id,
    )


def _gw(sink: AuditSink) -> Gateway:
    return (
        Gateway.builder()
        .adapter(MockProvider(response="hi"))
        .model("mock")
        .use(AuditInterceptor(sink))
        .build()
    )


async def test_subject_id_persisted_from_request_metadata() -> None:
    sink = CollectingSink()
    await _gw(sink).complete(
        messages=[{"role": "user", "content": "q"}],
        metadata={"subject_id": "user-123"},
    )
    assert sink.records[0].subject_id == "user-123"


async def test_subject_id_none_when_absent() -> None:
    sink = CollectingSink()
    await _gw(sink).complete(messages=[{"role": "user", "content": "q"}])
    assert sink.records[0].subject_id is None


async def test_jsonl_purge_removes_matching_and_returns_count(tmp_path) -> None:
    path = tmp_path / "audit.jsonl"
    sink = JsonlSink(path)
    await sink.write(_rec("u1", "t1"))
    await sink.write(_rec("u2", "t2"))
    await sink.write(_rec("u1", "t3"))

    removed = await sink.purge("u1")

    assert removed == 2
    lines = [json.loads(x) for x in path.read_text().splitlines() if x.strip()]
    assert len(lines) == 1
    assert lines[0]["subject_id"] == "u2"


async def test_jsonl_purge_no_match_returns_zero(tmp_path) -> None:
    path = tmp_path / "audit.jsonl"
    sink = JsonlSink(path)
    await sink.write(_rec("u1"))
    assert await sink.purge("nobody") == 0
    assert len([x for x in path.read_text().splitlines() if x.strip()]) == 1


async def test_purge_missing_file_returns_zero(tmp_path) -> None:
    sink = JsonlSink(tmp_path / "none.jsonl")
    assert await sink.purge("u1") == 0


async def test_stdout_sink_purge_is_noop() -> None:
    assert await StdoutSink().purge("u1") == 0
