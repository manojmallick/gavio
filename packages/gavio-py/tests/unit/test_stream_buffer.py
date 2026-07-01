"""Tests for streaming reliability / StreamBuffer (F-REL-06)."""

from __future__ import annotations

import hashlib

from gavio import Gateway
from gavio.context import InterceptorContext
from gavio.interceptors.audit import AuditInterceptor, AuditRecord
from gavio.interceptors.audit.sink import AuditSink
from gavio.interceptors.base import Interceptor
from gavio.interceptors.reliability import StreamBuffer
from gavio.response import GavioResponse


class CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


class ShoutInterceptor(Interceptor):
    """A post-interceptor that rewrites the response content."""

    @property
    def name(self) -> str:
        return "shout"

    async def after(self, response: GavioResponse, ctx: InterceptorContext) -> GavioResponse:
        response.content = response.content.upper()
        return response


def test_stream_buffer_accumulates():
    buf = StreamBuffer()
    assert buf.text() == "" and len(buf) == 0
    buf.append("ab")
    buf.append("cd")
    assert buf.text() == "abcd"
    assert len(buf) == 4


async def test_stream_emits_buffered_content():
    gw = Gateway.builder().dev_mode(True).build()
    chunks = [c async for c in gw.stream(messages=[{"role": "user", "content": "hi there"}])]
    full = "".join(chunks)
    assert full.strip() == "[mock reply] hi there"


async def test_post_interceptor_runs_on_full_buffered_response():
    sink = CollectingSink()
    gw = Gateway.builder().dev_mode(True).use(AuditInterceptor(sink=sink)).build()
    full = "".join(
        [c async for c in gw.stream(messages=[{"role": "user", "content": "hello world"}])]
    )

    # Audit (a post-interceptor) saw the complete response, not a partial chunk.
    assert len(sink.records) == 1
    assert sink.records[0].response_hash == hashlib.sha256(full.encode()).hexdigest()
    assert "audit" in sink.records[0].interceptors_fired


async def test_post_interceptor_can_rewrite_before_emit():
    # A rewrite in `after` must be visible to the caller — proof the stream is
    # buffered before post-interceptors run, not emitted raw.
    gw = Gateway.builder().dev_mode(True).use(ShoutInterceptor()).build()
    full = "".join([c async for c in gw.stream(messages=[{"role": "user", "content": "quiet"}])])
    assert full == full.upper()
    assert "[MOCK REPLY] QUIET" in full
