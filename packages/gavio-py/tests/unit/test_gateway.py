"""End-to-end Gateway tests in dev mode (no network, no keys)."""

from __future__ import annotations

import pytest

from gavio import ConfigurationError, Gateway, Provider
from gavio.interceptors.pii import PiiGuard
from gavio.interceptors.reliability import RetryInterceptor, TimeoutPolicy
from gavio.providers.mock import MockProvider


async def test_dev_mode_roundtrip_with_pii_restore():
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(PiiGuard())
        .build()
    )
    resp = await gw.complete(
        messages=[{"role": "user", "content": "mail jan@example.com please"}],
        agent_id="demo",
    )
    # MockProvider echoes the (redacted) prompt; PII restore puts the email back.
    assert "jan@example.com" in resp.content
    assert resp.audit is not None
    assert "EMAIL" in resp.audit.pii_entity_types
    assert resp.audit.agent_id == "demo"
    assert resp.provider == "mock"
    assert resp.trace_id == resp.audit.trace_id


async def test_audit_record_has_hashes_not_content():
    gw = Gateway.builder().dev_mode(True).build()
    resp = await gw.complete(messages=[{"role": "user", "content": "secret stuff"}])
    record = resp.audit
    assert len(record.prompt_hash) == 64  # sha256 hex
    assert len(record.response_hash) == 64
    assert record.schema_version == "1.0"


async def test_interceptors_fired_recorded():
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(PiiGuard())
        .use(TimeoutPolicy(timeout_seconds=5))
        .use(RetryInterceptor(max_attempts=2, base_delay_ms=1))
        .build()
    )
    resp = await gw.complete(messages=[{"role": "user", "content": "hi"}])
    fired = resp.interceptors_fired
    assert "pii_guard" in fired
    assert "audit" in fired
    assert "timeout" in fired
    assert "retry" in fired


async def test_builder_requires_provider_without_dev_mode():
    with pytest.raises(ConfigurationError):
        Gateway.builder().build()


async def test_explicit_adapter_and_model():
    gw = (
        Gateway.builder()
        .adapter(MockProvider(response="fixed"))
        .model("mock")
        .build()
    )
    resp = await gw.complete(messages=[{"role": "user", "content": "anything"}])
    assert resp.content == "fixed"


def test_complete_sync_outside_loop():
    # Plain sync test — complete_sync() spins up its own event loop.
    gw = Gateway.builder().dev_mode(True).build()
    resp = gw.complete_sync(messages=[{"role": "user", "content": "sync call"}])
    assert "sync call" in resp.content


async def test_unavailable_provider_raises():
    with pytest.raises(ConfigurationError):
        Gateway.builder().provider(Provider.GEMINI).build()


async def test_dry_run_does_not_redact():
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .dry_run(True)
        .use(PiiGuard())
        .build()
    )
    resp = await gw.complete(
        messages=[{"role": "user", "content": "mail jan@example.com"}]
    )
    # In dry-run the request is never modified, so the echo keeps the raw email.
    assert "jan@example.com" in resp.content
