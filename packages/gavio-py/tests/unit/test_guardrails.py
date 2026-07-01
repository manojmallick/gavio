"""Tests for guardrails (F-QUA-01 schema, F-QUA-02 regex)."""

from __future__ import annotations

import pytest

from gavio import Gateway
from gavio.exceptions import GuardrailViolationError
from gavio.interceptors.guardrails import (
    GuardrailsInterceptor,
    JsonSchemaValidator,
    RegexDenylistValidator,
)
from gavio.interceptors.guardrails.validators import RegexAllowlistValidator
from gavio.providers.mock import MockProvider


def _gw(response, *interceptors):
    b = Gateway.builder().adapter(MockProvider(response=response)).model("mock")
    for i in interceptors:
        b = b.use(i)
    return b.build()


async def test_json_schema_pass():
    schema = {
        "type": "object",
        "required": ["answer"],
        "properties": {"answer": {"type": "string"}},
    }
    gw = _gw('{"answer": "42"}', GuardrailsInterceptor([JsonSchemaValidator(schema)]))
    r = await gw.complete(messages=[{"role": "user", "content": "q"}])
    assert r.content == '{"answer": "42"}'


async def test_json_schema_fail_raises():
    schema = {"type": "object", "required": ["answer"]}
    guard = GuardrailsInterceptor([JsonSchemaValidator(schema)], on_failure="error")
    gw = _gw('{"wrong": 1}', guard)
    with pytest.raises(GuardrailViolationError):
        await gw.complete(messages=[{"role": "user", "content": "q"}])


async def test_json_schema_invalid_json_fails():
    gw = _gw("not json at all", GuardrailsInterceptor([JsonSchemaValidator({"type": "object"})]))
    with pytest.raises(GuardrailViolationError):
        await gw.complete(messages=[{"role": "user", "content": "q"}])


async def test_regex_denylist_blocks():
    gw = _gw(
        "contact competitor_name for details",
        GuardrailsInterceptor([RegexDenylistValidator([r"(?i)competitor_name"])]),
    )
    with pytest.raises(GuardrailViolationError):
        await gw.complete(messages=[{"role": "user", "content": "q"}])


async def test_regex_allowlist_requires_match():
    gw = _gw("hello", GuardrailsInterceptor([RegexAllowlistValidator([r"^\{.*\}$"])]))
    with pytest.raises(GuardrailViolationError):
        await gw.complete(messages=[{"role": "user", "content": "q"}])


async def test_on_failure_warn_returns_response():
    gw = _gw(
        "bad output",
        GuardrailsInterceptor([RegexDenylistValidator([r"bad"])], on_failure="warn"),
    )
    r = await gw.complete(messages=[{"role": "user", "content": "q"}])
    assert r.content == "bad output"  # warned, not raised
