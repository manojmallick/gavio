"""Tests for prompt lineage (F-OBS-04)."""

from __future__ import annotations

from gavio import Gateway, GavioRequest, PromptLineage, Provider, RagChunk
from gavio.interceptors.audit import AuditInterceptor, AuditRecord
from gavio.interceptors.audit.sink import AuditSink


class CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


def _lineage() -> PromptLineage:
    return PromptLineage(
        template_id="support-reply",
        template_version="v3",
        variables={"customer": "Ada", "tier": "gold"},
        rag_chunks=[
            RagChunk(source="doc://kb/refunds", chunk_id="c1", score=0.92),
            RagChunk(source="doc://kb/shipping"),
        ],
    )


def test_rag_chunk_carries_source_reference_only():
    chunk = RagChunk(source="doc://kb/refunds", chunk_id="c1", score=0.92)
    d = chunk.to_dict()
    assert d == {"source": "doc://kb/refunds", "chunk_id": "c1", "score": 0.92}
    # A source reference — never the retrieved text.
    assert "text" not in d and "content" not in d


def test_lineage_serialises_to_nested_dict():
    d = _lineage().to_dict()
    assert d["template_id"] == "support-reply"
    assert d["template_version"] == "v3"
    assert d["variables"] == {"customer": "Ada", "tier": "gold"}
    assert d["rag_chunks"][0]["source"] == "doc://kb/refunds"
    assert d["rag_chunks"][1] == {"source": "doc://kb/shipping", "chunk_id": None, "score": None}


def test_lineage_survives_copy_with_messages():
    lin = _lineage()
    req = GavioRequest(
        messages=[{"role": "user", "content": "hi"}],
        model="mock",
        provider=Provider.MOCK,
        lineage=lin,
    )
    copy = req.copy_with_messages([{"role": "user", "content": "redacted"}])
    assert copy.lineage is lin


async def test_lineage_flows_into_audit_record():
    sink = CollectingSink()
    gw = Gateway.builder().dev_mode(True).use(AuditInterceptor(sink=sink)).build()
    await gw.complete(messages=[{"role": "user", "content": "hi"}], lineage=_lineage())

    assert len(sink.records) == 1
    rec = sink.records[0]
    assert rec.lineage is not None
    assert rec.lineage.template_id == "support-reply"
    assert rec.to_dict()["lineage"]["rag_chunks"][0]["source"] == "doc://kb/refunds"


async def test_audit_record_lineage_is_none_when_absent():
    sink = CollectingSink()
    gw = Gateway.builder().dev_mode(True).use(AuditInterceptor(sink=sink)).build()
    await gw.complete(messages=[{"role": "user", "content": "hi"}])

    rec = sink.records[0]
    assert rec.lineage is None
    assert rec.to_dict()["lineage"] is None


def test_lineage_participates_in_content_hash():
    base = dict(
        trace_id="t1",
        provider="mock",
        model="mock",
        timestamp_utc="2026-07-01T00:00:00+00:00",
    )
    without = AuditRecord(**base)
    with_lineage = AuditRecord(**base, lineage=_lineage())
    other_lineage = AuditRecord(**base, lineage=PromptLineage(template_id="different"))

    assert without.content_hash() != with_lineage.content_hash()
    assert with_lineage.content_hash() != other_lineage.content_hash()
