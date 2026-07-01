"""Tests for InterceptorChain ordering, retry, timeout, and fallback."""

from __future__ import annotations

import pytest

from gavio.context import InterceptorContext
from gavio.exceptions import ProviderUnavailableError, ServerError
from gavio.exceptions import TimeoutError as GavioTimeoutError
from gavio.interceptors.base import Interceptor
from gavio.interceptors.chain import InterceptorChain
from gavio.interceptors.reliability import (
    FallbackChain,
    RetryInterceptor,
    TimeoutPolicy,
)
from gavio.request import GavioRequest
from gavio.response import GavioResponse
from gavio.types import Provider


def _req():
    return GavioRequest(
        messages=[{"role": "user", "content": "hi"}],
        model="mock",
        provider=Provider.MOCK,
    )


def _resp(content="ok"):
    return GavioResponse(trace_id="t", content=content, model="mock", provider="mock")


class _Recorder(Interceptor):
    def __init__(self, name, log):
        self._name = name
        self._log = log

    @property
    def name(self):
        return self._name

    async def before(self, request, ctx):
        self._log.append(f"{self._name}.before")
        return request

    async def after(self, response, ctx):
        self._log.append(f"{self._name}.after")
        return response


async def test_chain_onion_ordering():
    log: list[str] = []
    chain = InterceptorChain([_Recorder("a", log), _Recorder("b", log)])

    async def executor(req):
        log.append("provider")
        return _resp()

    await chain.execute(_req(), InterceptorContext(trace_id="t"), executor)
    assert log == [
        "a.before",
        "b.before",
        "provider",
        "b.after",
        "a.after",
    ]


async def test_retry_succeeds_after_transient_failures():
    attempts = {"n": 0}

    async def flaky(req):
        attempts["n"] += 1
        if attempts["n"] < 3:
            raise ServerError("boom")
        return _resp("recovered")

    retry = RetryInterceptor(max_attempts=3, base_delay_ms=1, jitter=False)
    resp = await retry.around(_req(), InterceptorContext(trace_id="t"), flaky)
    assert resp.content == "recovered"
    assert attempts["n"] == 3


async def test_retry_exhausts_and_raises_last_error():
    async def always_fail(req):
        raise ServerError("nope")

    retry = RetryInterceptor(max_attempts=2, base_delay_ms=1, jitter=False)
    with pytest.raises(ServerError):
        await retry.around(_req(), InterceptorContext(trace_id="t"), always_fail)


async def test_timeout_policy_raises():
    import asyncio

    async def slow(req):
        await asyncio.sleep(0.2)
        return _resp()

    policy = TimeoutPolicy(timeout_seconds=0.01)
    with pytest.raises(GavioTimeoutError):
        await policy.around(_req(), InterceptorContext(trace_id="t"), slow)


async def test_fallback_uses_secondary_adapter():
    from gavio.providers.mock import MockProvider

    fallback = FallbackChain(fallbacks=[MockProvider(response="from-fallback")])

    async def primary_fails(req):
        raise ProviderUnavailableError("down")

    resp = await fallback.around(
        _req(), InterceptorContext(trace_id="t"), primary_fails
    )
    assert resp.content == "from-fallback"
    assert resp.provider == "mock"
