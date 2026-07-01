"""Tests for AuditRecord, StdoutSink, pricing, and GavioTestKit."""

from __future__ import annotations

import io

from gavio.interceptors.audit import AuditRecord, StdoutSink
from gavio.interceptors.pii import PiiGuard
from gavio.pricing import PricingProvider, estimate_tokens
from gavio.testing import GavioTestKit, MockProvider
from gavio.types import TokenUsage


def test_audit_record_serialises_without_content():
    rec = AuditRecord(
        trace_id="t-1",
        provider="mock",
        model="mock",
        timestamp_utc=AuditRecord.now_utc(),
        prompt_hash=AuditRecord.hash_text("hello"),
        token_usage=TokenUsage(prompt_tokens=10, completion_tokens=5),
    )
    data = rec.to_dict()
    assert data["token_usage"]["total_tokens"] == 15
    assert data["schema_version"] == "1.0"
    assert "hello" not in rec.to_json()  # only the hash, never the text


async def test_stdout_sink_writes_line():
    buf = io.StringIO()
    sink = StdoutSink(pretty=True, stream=buf)
    rec = AuditRecord(
        trace_id="trace-abc",
        provider="mock",
        model="mock",
        timestamp_utc=AuditRecord.now_utc(),
    )
    await sink.write(rec)
    out = buf.getvalue()
    assert "gavio:audit" in out
    assert "mock/mock" in out


def test_pricing_known_and_unknown_models():
    p = PricingProvider()
    usage = TokenUsage(prompt_tokens=1000, completion_tokens=1000)
    cost = p.estimate("gpt-4o", usage)
    assert cost == round(0.0025 + 0.010, 8)
    assert p.estimate("totally-unknown-model", usage) == 0.0


def test_estimate_tokens():
    assert estimate_tokens("") == 0
    assert estimate_tokens("abcd" * 10) == 10


async def test_testkit_detects_pii_and_redacted_request():
    kit = GavioTestKit(
        interceptors=[PiiGuard()],
        provider=MockProvider(response="done [EMAIL_1]"),
    )
    resp = await kit.run(messages=[{"role": "user", "content": "to jan@example.com"}])
    assert kit.pii_detected("EMAIL")
    assert "jan@example.com" not in kit.redacted_request.messages[0]["content"]
    # restore-on-response puts it back in the final content
    assert resp.content == "done jan@example.com"
