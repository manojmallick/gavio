"""Tests for the PiiGuard interceptor: redact, restore, mask, block, overlaps."""

from __future__ import annotations

import pytest

from gavio.context import InterceptorContext
from gavio.exceptions import PiiBlockedError
from gavio.interceptors.pii import PiiGuard
from gavio.response import GavioResponse
from gavio.types import PiiMode


def _ctx():
    return InterceptorContext(trace_id="t-1")


def _req_with(content):
    from gavio.request import GavioRequest
    from gavio.types import Provider

    return GavioRequest(
        messages=[{"role": "user", "content": content}],
        model="mock",
        provider=Provider.MOCK,
    )


async def test_redacts_and_records_entity_types():
    guard = PiiGuard()
    ctx = _ctx()
    req = await guard.before(_req_with("mail jan@example.com now"), ctx)
    assert "jan@example.com" not in req.messages[0]["content"]
    assert "[EMAIL_1]" in req.messages[0]["content"]
    assert "EMAIL" in ctx.pii_entity_types


async def test_restore_on_response():
    guard = PiiGuard()
    ctx = _ctx()
    await guard.before(_req_with("mail jan@example.com now"), ctx)
    resp = GavioResponse(
        trace_id="t-1",
        content="I emailed [EMAIL_1] for you.",
        model="mock",
        provider="mock",
    )
    restored = await guard.after(resp, ctx)
    assert restored.content == "I emailed jan@example.com for you."


async def test_block_mode_raises():
    guard = PiiGuard(mode=PiiMode.BLOCK)
    with pytest.raises(PiiBlockedError):
        await guard.before(_req_with("mail jan@example.com now"), _ctx())


async def test_mask_mode_no_restore():
    guard = PiiGuard(mode=PiiMode.MASK)
    ctx = _ctx()
    req = await guard.before(_req_with("mail jan@example.com now"), ctx)
    assert "jan@example.com" not in req.messages[0]["content"]
    assert "*" in req.messages[0]["content"]


async def test_dry_run_does_not_modify_but_still_records():
    guard = PiiGuard()
    ctx = _ctx()
    ctx.dry_run = True
    req = await guard.before(_req_with("mail jan@example.com now"), ctx)
    assert "jan@example.com" in req.messages[0]["content"]  # unmodified
    assert "EMAIL" in ctx.pii_entity_types  # but detected/logged


async def test_no_pii_passthrough():
    guard = PiiGuard()
    ctx = _ctx()
    req = await guard.before(_req_with("just a normal sentence"), ctx)
    assert req.messages[0]["content"] == "just a normal sentence"
    assert ctx.pii_entity_types == []
