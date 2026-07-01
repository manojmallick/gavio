"""Tests for CircuitBreaker (F-REL-03)."""

from __future__ import annotations

import asyncio

import pytest

from gavio.context import InterceptorContext
from gavio.exceptions import CircuitOpenError, ServerError
from gavio.interceptors.reliability import CircuitBreaker, CircuitState
from gavio.request import GavioRequest
from gavio.response import GavioResponse
from gavio.types import Provider


def _req():
    return GavioRequest(
        messages=[{"role": "user", "content": "hi"}], model="mock", provider=Provider.MOCK
    )


def _ctx():
    return InterceptorContext(trace_id="t")


def _ok(_req):
    return GavioResponse(trace_id="t", content="ok", model="mock", provider="mock")


async def _fail(_req):
    raise ServerError("boom")


async def test_opens_after_threshold_then_fast_fails():
    cb = CircuitBreaker(failure_threshold=3, recovery_timeout_seconds=60)

    for _ in range(3):
        with pytest.raises(ServerError):
            await cb.around(_req(), _ctx(), _fail)

    assert cb.state == CircuitState.OPEN

    # Now open: rejects WITHOUT calling the provider.
    called = {"n": 0}

    async def spy(_req):
        called["n"] += 1
        return _ok(_req)

    with pytest.raises(CircuitOpenError):
        await cb.around(_req(), _ctx(), spy)
    assert called["n"] == 0


async def test_half_open_recovers_on_success():
    cb = CircuitBreaker(failure_threshold=1, recovery_timeout_seconds=0.05)

    with pytest.raises(ServerError):
        await cb.around(_req(), _ctx(), _fail)
    assert cb.state == CircuitState.OPEN

    await asyncio.sleep(0.06)  # recovery window elapses → half-open on next call

    async def ok(_req):
        return _ok(_req)

    resp = await cb.around(_req(), _ctx(), ok)
    assert resp.content == "ok"
    assert cb.state == CircuitState.CLOSED


async def test_half_open_reopens_on_failure():
    cb = CircuitBreaker(failure_threshold=1, recovery_timeout_seconds=0.05)
    with pytest.raises(ServerError):
        await cb.around(_req(), _ctx(), _fail)
    await asyncio.sleep(0.06)
    # Probe fails → straight back to OPEN.
    with pytest.raises(ServerError):
        await cb.around(_req(), _ctx(), _fail)
    assert cb.state == CircuitState.OPEN
