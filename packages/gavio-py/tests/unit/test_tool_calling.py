"""Normalised tool calling (F-ADP-03): shared vectors, schemas, privacy, audit, cache, bridge."""

from __future__ import annotations

import dataclasses
import json
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest

from gavio import ConfigurationError, Gateway, MalformedToolCallError, PiiBlockedError
from gavio.context import InterceptorContext
from gavio.interceptors.audit import AuditInterceptor, AuditRecord, AuditSink, verify_chain
from gavio.interceptors.cache import SemanticCache
from gavio.interceptors.pii import PiiGuard
from gavio.interceptors.tool_runtime import (
    analyze_provider_tool_calls,
    analyze_tool_runtime,
    tool_calls_to_runtime_calls,
)
from gavio.providers import anthropic, azure_openai, gemini, ollama, openai, openrouter
from gavio.providers.anthropic import AnthropicAdapter
from gavio.providers.azure_openai import AzureOpenAIAdapter
from gavio.providers.base import ProviderAdapter
from gavio.providers.gemini import GeminiAdapter
from gavio.providers.mock import MockProvider
from gavio.providers.ollama import OllamaAdapter
from gavio.providers.openai import OpenAIAdapter
from gavio.providers.openrouter import OpenRouterAdapter
from gavio.request import GavioRequest
from gavio.response import GavioResponse
from gavio.types import (
    FinishReason,
    PiiMode,
    Provider,
    TokenUsage,
    ToolCall,
    ToolResult,
)

# repo_root/packages/gavio-py/tests/unit/test_tool_calling.py -> repo_root
_ROOT = Path(__file__).resolve().parents[4]
_VECTORS = _ROOT / "test-vectors" / "tool-calling"
_SPEC = _ROOT / "spec"

# vector file -> (adapter modules that must pass it, adapter factory per module)
_FILES: dict[str, list[tuple[ModuleType, Any]]] = {
    "openai.json": [
        (openai, lambda: OpenAIAdapter(api_key="k")),
        (openrouter, lambda: OpenRouterAdapter(api_key="k")),
    ],
    "azure.json": [
        (
            azure_openai,
            lambda: AzureOpenAIAdapter(api_key="k", endpoint="https://x.openai.azure.com"),
        )
    ],
    "anthropic.json": [(anthropic, lambda: AnthropicAdapter(api_key="k"))],
    "gemini.json": [(gemini, lambda: GeminiAdapter(api_key="k"))],
    "ollama.json": [(ollama, lambda: OllamaAdapter())],
}
_ERRORS = {"MalformedToolCall": MalformedToolCallError, "Configuration": ConfigurationError}
_IBAN = "NL91ABNA0417164300"  # synthetic, checksum-valid


def _doc(file: str) -> dict[str, Any]:
    return json.loads((_VECTORS / file).read_text())


def _cases(kind: str) -> list[Any]:
    params = []
    for file, modules in _FILES.items():
        for module, _factory in modules:
            for case in _doc(file).get(kind, []):
                params.append(
                    pytest.param(module, case, id=f"{module.__name__.split('.')[-1]}:{case['id']}")
                )
    return params


def _request(data: dict[str, Any]) -> GavioRequest:
    """Build a GavioRequest from a spec-shaped (camelCase) vector request."""
    options = data.get("options", {})
    return GavioRequest(
        messages=data["messages"],
        model=data["model"],
        provider=data["provider"],
        options={"temperature": options["temperature"], "max_tokens": options["maxTokens"]},
        tools=data.get("tools"),
        tool_choice=data.get("toolChoice"),
    )


# ---- shared vectors ---------------------------------------------------------------


@pytest.mark.parametrize(("module", "case"), _cases("requestCases"))
def test_request_vectors_normalised_to_native_payload(module: ModuleType, case: dict) -> None:
    assert module.to_provider_payload(_request(case["request"])) == case["expectedPayload"]


@pytest.mark.parametrize(("module", "case"), _cases("requestErrorCases"))
def test_request_error_vectors(module: ModuleType, case: dict) -> None:
    with pytest.raises(_ERRORS[case["expectedError"]]):
        module.to_provider_payload(_request(case["request"]))


@pytest.mark.parametrize(("module", "case"), _cases("responseCases"))
def test_response_vectors_native_to_normalised(module: ModuleType, case: dict) -> None:
    completion = module.from_provider_response(case["providerResponse"])
    assert completion.to_wire() == case["expected"]


@pytest.mark.parametrize(("module", "case"), _cases("errorCases"))
def test_malformed_response_vectors_raise_without_leaking_arguments(
    module: ModuleType, case: dict
) -> None:
    with pytest.raises(_ERRORS[case["expectedError"]]) as error:
        module.from_provider_response(case["providerResponse"])
    # The message names the tool at most — never the arguments, which may hold PII.
    assert '{"city"' not in str(error.value)
    assert "Paris" not in str(error.value)


@pytest.mark.parametrize("case", _doc("arguments-hash.json")["cases"], ids=lambda c: c["id"])
def test_arguments_hash_vectors(case: dict) -> None:
    from gavio.types import canonical_json

    call = ToolCall(id="c", name="t", arguments=case["arguments"])
    assert canonical_json(call.arguments) == case["expectedCanonical"]
    assert call.arguments_hash() == case["expectedHash"]


def test_arguments_hash_ignores_key_order() -> None:
    cases = {c["id"]: c for c in _doc("arguments-hash.json")["cases"]}
    assert (
        cases["key-order-does-not-matter-a"]["expectedHash"]
        == cases["key-order-does-not-matter-b"]["expectedHash"]
    )


@pytest.mark.parametrize(
    ("file", "module", "factory"),
    [
        pytest.param(file, module, factory, id=module.__name__.split(".")[-1])
        for file, modules in _FILES.items()
        for module, factory in modules
    ],
)
async def test_adapter_complete_returns_tool_calls(
    monkeypatch: pytest.MonkeyPatch, file: str, module: ModuleType, factory: Any
) -> None:
    """complete() wires the pure functions into a GavioResponse with tool calls."""
    doc = _doc(file)
    request_case = next(c for c in doc["requestCases"] if c["id"] == "declares-tools-auto")
    response_case = next(
        c for c in doc["responseCases"] if "call" in c["id"] and c["expected"]["toolCalls"]
    )
    sent: dict[str, Any] = {}

    async def fake_post_json(url: str, payload: dict, headers: dict, timeout: float = 30.0):
        sent["payload"] = payload
        return response_case["providerResponse"]

    monkeypatch.setattr(module, "post_json", fake_post_json)
    response = await factory().complete(_request(request_case["request"]))

    assert sent["payload"] == request_case["expectedPayload"]
    expected = response_case["expected"]
    assert [c.to_dict() for c in response.tool_calls] == expected["toolCalls"]
    assert response.finish_reason == FinishReason(expected["finishReason"])
    assert response.content == expected["content"]
    assert response.usage.prompt_tokens == expected["usage"]["promptTokens"]
    assert response.model_version == (expected["modelVersion"] or request_case["request"]["model"])


# ---- schemas ----------------------------------------------------------------------


def _validators() -> Any:
    jsonschema = pytest.importorskip("jsonschema")
    referencing = pytest.importorskip("referencing")
    from referencing.jsonschema import DRAFT202012

    resources = []
    for path in _SPEC.glob("*.schema.json"):
        doc = json.loads(path.read_text())
        resource = referencing.Resource.from_contents(doc, default_specification=DRAFT202012)
        # Some existing schemas $ref siblings by file name rather than by $id.
        resources.append((doc["$id"], resource))
        resources.append((f"https://gavio.io/schema/{path.name}", resource))
    registry = referencing.Registry().with_resources(resources)

    def validator(name: str) -> Any:
        schema = json.loads((_SPEC / f"{name}.schema.json").read_text())
        return jsonschema.Draft202012Validator(schema, registry=registry)

    return validator


def test_vector_requests_and_responses_validate_against_the_spec() -> None:
    validator = _validators()
    request_schema = validator("GavioRequest")
    response_schema = validator("GavioResponse")
    tool_result_schema = validator("ToolResult")
    checked = 0
    for file in _FILES:
        doc = _doc(file)
        for case in doc["requestCases"] + doc.get("requestErrorCases", []):
            request_schema.validate(case["request"])
            for message in case["request"]["messages"]:
                if message["role"] == "tool" and "toolCallId" in message:
                    tool_result_schema.validate({k: v for k, v in message.items() if k != "role"})
            checked += 1
        for case in doc["responseCases"]:
            response = {
                "traceId": "t",
                "model": "m",
                "provider": doc["provider"],
                **{k: v for k, v in case["expected"].items() if v is not None},
            }
            response_schema.validate(response)
            checked += 1
    assert checked > 60


def test_tool_call_and_result_schemas_reject_malformed_documents() -> None:
    validator = _validators()
    tool_call = validator("ToolCall")
    tool_result = validator("ToolResult")
    tool_call.validate({"id": "c1", "name": "t", "arguments": {}})
    for bad in (
        {"id": "c1", "name": "t"},  # arguments required
        {"id": "c1", "name": "t", "arguments": "{}"},  # must be an object, not a JSON string
        {"id": "", "name": "t", "arguments": {}},
        {"id": "c1", "name": "t", "arguments": {}, "extra": 1},
    ):
        assert not tool_call.is_valid(bad), bad
    tool_result.validate({"toolCallId": "c1", "content": "ok", "isError": False})
    assert not tool_result.is_valid({"content": "ok"})
    assert not tool_result.is_valid({"toolCallId": "c1", "content": {"a": 1}})


def test_audit_schema_accepts_tool_name_and_hash_only() -> None:
    audit = _validators()("AuditRecord")
    call = ToolCall(id="c1", name="transfer", arguments={"iban": _IBAN})
    record = {
        "traceId": "t",
        "provider": "openai",
        "model": "m",
        "timestampUtc": "2026-01-01T00:00:00+00:00",
        "promptHash": "a" * 64,
        "responseHash": "b" * 64,
        "tokenUsage": {"promptTokens": 1, "completionTokens": 1, "totalTokens": 2},
        "schemaVersion": "1.0",
        "toolCalls": [{"name": call.name, "argumentsHash": call.arguments_hash()}],
    }
    audit.validate(record)
    record["toolCalls"][0]["arguments"] = {"iban": _IBAN}
    assert not audit.is_valid(record)


# ---- types ------------------------------------------------------------------------


def test_tool_result_round_trip_through_a_message() -> None:
    call = ToolCall(id="c1", name="get_weather", arguments={"city": "Paris"})
    assert call.result("18C").to_message() == {
        "role": "tool",
        "tool_call_id": "c1",
        "content": "18C",
        "name": "get_weather",
    }
    failed = call.result("boom", is_error=True)
    assert failed == ToolResult("c1", "boom", "get_weather", True)
    assert failed.to_message()["is_error"] is True


def test_tool_call_from_dict_validates() -> None:
    assert ToolCall.from_dict({"id": "c", "name": "t"}) == ToolCall("c", "t", {})
    with pytest.raises(TypeError):
        ToolCall.from_dict({"id": "c", "name": "t", "arguments": "{}"})
    with pytest.raises(TypeError):
        ToolCall.from_dict("not a dict")  # type: ignore[arg-type]


def test_response_to_assistant_message_feeds_the_next_request() -> None:
    call = ToolCall(id="call_1", name="get_weather", arguments={"city": "Paris"})
    response = GavioResponse(
        trace_id="t", content="", model="m", provider="openai", tool_calls=[call]
    )
    messages = [
        {"role": "user", "content": "weather?"},
        response.to_assistant_message(),
        call.result('{"t":18}').to_message(),
    ]
    payload = openai.to_provider_payload(
        GavioRequest(messages=messages, model="gpt-4o", provider=Provider.OPENAI)
    )
    assert payload["messages"][1]["tool_calls"][0]["function"]["arguments"] == '{"city":"Paris"}'
    assert payload["messages"][2] == {
        "role": "tool",
        "tool_call_id": "call_1",
        "content": '{"t":18}',
    }
    plain = GavioResponse(trace_id="t", content="hi", model="m", provider="openai")
    assert plain.to_assistant_message() == {"role": "assistant", "content": "hi"}


def test_wire_spelled_messages_are_accepted() -> None:
    wire = [
        {"role": "user", "content": "hi"},
        {
            "role": "assistant",
            "content": "",
            "toolCalls": [{"id": "c1", "name": "t", "arguments": {}}],
        },
        {"role": "tool", "toolCallId": "c1", "isError": True, "content": "x"},
    ]
    snake = [
        wire[0],
        {"role": "assistant", "content": "", "tool_calls": wire[1]["toolCalls"]},
        {"role": "tool", "tool_call_id": "c1", "is_error": True, "content": "x"},
    ]
    for module in (openai, anthropic, gemini, ollama):
        a = module.to_provider_payload(GavioRequest(messages=wire, model="m", provider="mock"))
        b = module.to_provider_payload(GavioRequest(messages=snake, model="m", provider="mock"))
        assert a == b, module.__name__


def test_prompt_text_includes_tool_calls_but_is_unchanged_without_them() -> None:
    plain = GavioRequest(
        messages=[{"role": "user", "content": "a"}, {"role": "assistant", "content": "b"}],
        model="m",
        provider="mock",
    )
    assert plain.prompt_text() == "a\nb"
    with_calls = GavioRequest(
        messages=[
            {"role": "user", "content": "a"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"id": "c", "name": "t", "arguments": {"k": "v"}}],
            },
        ],
        model="m",
        provider="mock",
    )
    assert with_calls.prompt_text() == 'a\n\nt({"k":"v"})'


# ---- copy_with_* must carry every field ---------------------------------------------


def test_copy_with_messages_carries_every_request_field() -> None:
    request = GavioRequest(
        messages=[{"role": "user", "content": "hi"}],
        model="m",
        provider="openai",
        agent_id="a",
        parent_trace_id="p",
        session_id="s",
        options={"temperature": 0.1},
        metadata={"k": 1},
        images=[b"x"],
        tools=[{"name": "t"}],
        tool_choice={"name": "t"},
    )
    copy = request.copy_with_messages([{"role": "user", "content": "changed"}])
    for field in dataclasses.fields(GavioRequest):
        if field.name == "messages":
            continue
        assert getattr(copy, field.name) == getattr(request, field.name), field.name
    assert copy.tools is not request.tools and copy.options is not request.options
    assert copy.messages[0]["content"] == "changed"


def test_copy_with_content_carries_every_response_field() -> None:
    response = GavioResponse(
        trace_id="t",
        content="old",
        model="m",
        provider="openai",
        model_version="v",
        usage=TokenUsage(1, 2),
        cost_usd=0.5,
        latency_ms=7,
        cache_hit=True,
        interceptors_fired=["a"],
        metadata={"k": 1},
        embeddings=[[0.1]],
        tool_calls=[ToolCall("c", "t", {"a": 1})],
        finish_reason=FinishReason.TOOL_CALLS,
    )
    copy = response.copy_with_content("new")
    for field in dataclasses.fields(GavioResponse):
        if field.name == "content":
            continue
        assert getattr(copy, field.name) == getattr(response, field.name), field.name
    assert copy.content == "new"
    assert copy.tool_calls is not response.tool_calls


# ---- privacy ----------------------------------------------------------------------


def _history(arguments: dict[str, Any], tool_content: str = "ok") -> GavioRequest:
    return GavioRequest(
        messages=[
            {"role": "user", "content": "Pay the invoice"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"id": "call_1", "name": "transfer", "arguments": arguments}],
            },
            {"role": "tool", "tool_call_id": "call_1", "content": tool_content},
        ],
        model="m",
        provider="mock",
        tools=[
            {
                "name": "transfer",
                "description": "Send money",
                "inputSchema": {"type": "object", "properties": {"iban": {"type": "string"}}},
            }
        ],
    )


@pytest.mark.parametrize(
    "module", [openai, azure_openai, anthropic, gemini, ollama], ids=lambda m: m.__name__[-12:]
)
async def test_iban_in_tool_argument_is_redacted_before_the_payload_is_built(
    module: ModuleType,
) -> None:
    request = _history({"iban": _IBAN, "memo": f"to {_IBAN} please", "nested": {"l": [_IBAN]}})
    ctx = InterceptorContext(trace_id="t")
    redacted = await PiiGuard().before(request, ctx)
    payload = json.dumps(module.to_provider_payload(redacted), ensure_ascii=False)

    assert _IBAN not in payload
    assert "[IBAN_" in payload
    assert "IBAN" in ctx.pii_entity_types
    # The caller's request object is untouched.
    assert request.messages[1]["tool_calls"][0]["arguments"]["iban"] == _IBAN


async def test_tool_role_message_content_is_scanned() -> None:
    request = _history({"iban": "x"}, tool_content="sent to jan@example.com")
    redacted = await PiiGuard().before(request, InterceptorContext(trace_id="t"))
    assert redacted.messages[2]["content"] == "sent to [EMAIL_1]"


async def test_secret_and_numeric_identifier_in_tool_arguments_are_redacted() -> None:
    key = "sk-ant-" + "a1B2c3D4e5F6g7H8i9J0k1L2"  # synthetic, matches the key pattern
    request = _history({"api_key": key, "bsn": 111222333, "count": 3, "flag": True, "none": None})
    redacted = await PiiGuard().before(request, InterceptorContext(trace_id="t"))
    arguments = redacted.messages[1]["tool_calls"][0]["arguments"]
    assert key not in json.dumps(arguments)
    assert arguments["bsn"] == "[BSN_1]"
    assert (arguments["count"], arguments["flag"], arguments["none"]) == (3, True, None)


async def test_wire_spelled_tool_calls_are_scanned_too() -> None:
    request = GavioRequest(
        messages=[
            {"role": "user", "content": "hi"},
            {
                "role": "assistant",
                "content": "",
                "toolCalls": [{"id": "c", "name": "t", "arguments": {"iban": _IBAN}}],
            },
        ],
        model="m",
        provider="mock",
    )
    redacted = await PiiGuard().before(request, InterceptorContext(trace_id="t"))
    assert _IBAN not in json.dumps(redacted.messages)


async def test_block_mode_refuses_pii_in_tool_arguments() -> None:
    with pytest.raises(PiiBlockedError):
        await PiiGuard(mode=PiiMode.BLOCK).before(
            _history({"iban": _IBAN}), InterceptorContext(trace_id="t")
        )


async def test_dry_run_reports_but_does_not_rewrite_tool_arguments() -> None:
    request = _history({"iban": _IBAN})
    ctx = InterceptorContext(trace_id="t", dry_run=True)
    result = await PiiGuard().before(request, ctx)
    assert result is request
    assert "IBAN" in ctx.pii_entity_types


async def test_placeholders_are_restored_in_response_tool_call_arguments() -> None:
    guard = PiiGuard()
    ctx = InterceptorContext(trace_id="t")
    await guard.before(_history({"iban": _IBAN}), ctx)
    response = GavioResponse(
        trace_id="t",
        content="Sending to [IBAN_1]",
        model="m",
        provider="mock",
        tool_calls=[ToolCall("call_2", "transfer", {"iban": "[IBAN_1]", "amount": 5})],
        finish_reason=FinishReason.TOOL_CALLS,
    )
    restored = await guard.after(response, ctx)
    assert restored.content == f"Sending to {_IBAN}"
    assert restored.tool_calls == [ToolCall("call_2", "transfer", {"iban": _IBAN, "amount": 5})]
    assert restored.finish_reason is FinishReason.TOOL_CALLS
    # The original response is not mutated.
    assert response.tool_calls[0].arguments["iban"] == "[IBAN_1]"


async def test_mask_mode_does_not_restore_tool_arguments() -> None:
    guard = PiiGuard(mode=PiiMode.MASK)
    ctx = InterceptorContext(trace_id="t")
    await guard.before(_history({"iban": _IBAN}), ctx)
    response = GavioResponse(
        trace_id="t", content="", model="m", provider="mock",
        tool_calls=[ToolCall("c", "transfer", {"iban": "*" * 18})],
    )  # fmt: skip
    assert await guard.after(response, ctx) is response


class _ToolCallingProvider(MockProvider):
    """A mock provider that records what it was sent and answers with a tool call."""

    def __init__(self, tool_calls: list[ToolCall]) -> None:
        super().__init__(response="")
        self.seen: list[GavioRequest] = []
        self.calls = 0
        self._tool_calls = tool_calls

    async def complete(self, request: GavioRequest) -> GavioResponse:
        self.calls += 1
        self.seen.append(request)
        response = await super().complete(request)
        response.tool_calls = list(self._tool_calls)
        response.finish_reason = FinishReason.TOOL_CALLS
        return response


async def test_gateway_redacts_tool_arguments_restores_them_and_audits_hashes_only() -> None:
    provider = _ToolCallingProvider([ToolCall("call_2", "transfer", {"iban": "[IBAN_1]"})])
    gateway = (
        Gateway.builder()
        .adapter(provider)
        .model("mock")
        .use(AuditInterceptor(sink=_CapturingSink()))
        .use(PiiGuard())
        .build()
    )
    history = _history({"iban": _IBAN})

    response = await gateway.complete(history.messages, tools=history.tools, tool_choice="auto")

    sent = provider.seen[0]
    assert sent.tools == history.tools and sent.tool_choice == "auto"
    assert _IBAN not in json.dumps(openai.to_provider_payload(sent))
    # The caller sees the real value again...
    assert response.tool_calls == [ToolCall("call_2", "transfer", {"iban": _IBAN})]
    # ...but the audit record keeps only the tool name and the arguments hash.
    assert response.audit is not None
    assert response.audit.tool_calls == [
        {"name": "transfer", "arguments_hash": response.tool_calls[0].arguments_hash()}
    ]
    assert _IBAN not in response.audit.to_json()
    assert "[IBAN_1]" not in response.audit.to_json()
    assert "arguments\"" not in response.audit.to_json()


# ---- audit --------------------------------------------------------------------------


def test_audit_record_omits_tool_calls_when_empty_so_existing_chains_still_verify() -> None:
    def record(**extra: Any) -> AuditRecord:
        return AuditRecord(
            trace_id="t",
            provider="p",
            model="m",
            timestamp_utc="2026-01-01T00:00:00+00:00",
            **extra,
        )

    plain = record()
    assert "tool_calls" not in plain.to_dict()
    assert "tool_calls" not in plain.to_json()
    with_calls = record(tool_calls=[{"name": "t", "arguments_hash": "h"}])
    assert with_calls.to_dict()["tool_calls"] == [{"name": "t", "arguments_hash": "h"}]
    assert with_calls.content_hash() != plain.content_hash()

    first = record()
    second = record(
        previous_hash=first.content_hash(), tool_calls=[{"name": "t", "arguments_hash": "h"}]
    )
    third = record(previous_hash=second.content_hash())
    assert verify_chain([first, second, third])
    second.tool_calls[0]["arguments_hash"] = "tampered"
    assert not verify_chain([first, second, third])


async def test_audit_interceptor_without_tool_calls_records_none() -> None:
    gateway = Gateway.builder().dev_mode(True).build()
    response = await gateway.complete([{"role": "user", "content": "hi"}])
    assert response.audit is not None
    assert response.audit.tool_calls == []


class _CapturingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


async def test_audit_hash_changes_with_arguments_but_not_with_key_order() -> None:
    sink = _CapturingSink()
    audit = AuditInterceptor(sink=sink)
    for arguments in ({"a": 1, "b": 2}, {"b": 2, "a": 1}, {"a": 1, "b": 3}):
        response = GavioResponse(
            trace_id="t", content="", model="m", provider="p",
            tool_calls=[ToolCall("c", "t", arguments)],
        )  # fmt: skip
        await audit.after(response, InterceptorContext(trace_id="t"))
    hashes = [r.tool_calls[0]["arguments_hash"] for r in sink.records]
    assert hashes[0] == hashes[1] != hashes[2]


# ---- cache --------------------------------------------------------------------------


async def test_cache_never_replays_a_tool_call_response() -> None:
    provider = _ToolCallingProvider([ToolCall("c", "t", {"a": 1})])
    gateway = Gateway.builder().adapter(provider).model("mock").use(SemanticCache()).build()
    messages = [{"role": "user", "content": "do it"}]
    first = await gateway.complete(messages)
    second = await gateway.complete(messages)
    assert provider.calls == 2
    assert not first.cache_hit and not second.cache_hit
    assert second.tool_calls == [ToolCall("c", "t", {"a": 1})]


async def test_cache_key_separates_different_tool_sets() -> None:
    class Counting(MockProvider):
        calls = 0

        async def complete(self, request: GavioRequest) -> GavioResponse:
            type(self).calls += 1
            return await super().complete(request)

    gateway = (
        Gateway.builder().adapter(Counting(response="x")).model("mock").use(SemanticCache()).build()
    )
    messages = [{"role": "user", "content": "hello"}]
    await gateway.complete(messages, tools=[{"name": "a"}])
    await gateway.complete(messages, tools=[{"name": "a"}])  # same tools -> cached
    await gateway.complete(messages, tools=[{"name": "b"}])  # different tools -> miss
    await gateway.complete(messages, tools=[{"name": "a"}], tool_choice="required")  # miss
    await gateway.complete(messages)  # no tools -> miss
    assert Counting.calls == 4


# ---- Tool Runtime bridge ------------------------------------------------------------


def test_tool_calls_map_to_the_call_dicts_tool_runtime_accepts() -> None:
    calls = [ToolCall("call_1", "lookup_invoice", {"invoice_id": "INV-1"})]
    mapped = tool_calls_to_runtime_calls(calls, source="openai")
    assert mapped == [
        {
            "id": "call_1",
            "name": "lookup_invoice",
            "arguments": {"invoice_id": "INV-1"},
            "source": "openai",
        }
    ]
    mapped[0]["arguments"]["invoice_id"] = "changed"
    assert calls[0].arguments["invoice_id"] == "INV-1"  # the bridge copies
    with_result = tool_calls_to_runtime_calls(calls, results={"call_1": {"total": 5}})
    assert with_result[0]["result"] == {"total": 5}

    decision = analyze_tool_runtime({"calls": mapped})
    assert decision["call_count"] == 1
    assert decision["decisions"][0]["tool_name"] == "lookup_invoice"


def test_provider_tool_calls_are_validated_against_definitions_and_permissions() -> None:
    definitions = [
        {"name": "lookup_invoice", "permissions": ["read.billing"]},
        {"name": "delete_account", "permissions": ["write.accounts"], "risk": "destructive"},
    ]
    calls = [
        ToolCall("call_1", "lookup_invoice", {"invoice_id": "INV-1"}),
        ToolCall("call_2", "delete_account", {"account_id": "A-9"}),
    ]
    denied = analyze_provider_tool_calls(calls, definitions=definitions)
    assert [d["action"] for d in denied["decisions"]] == ["block", "require_approval"]
    assert denied["decisions"][0]["missing_permissions"] == ["read.billing"]

    granted = analyze_provider_tool_calls(
        calls[:1], {"permissions": ["read.billing"]}, definitions=definitions
    )
    assert granted["decisions"][0]["action"] == "allow"
    assert granted["violations"] == []


def test_bridge_keeps_calls_already_in_the_tool_context() -> None:
    context = {"tool_calls": [{"id": "old", "name": "earlier", "arguments": {}}]}
    decision = analyze_provider_tool_calls([ToolCall("new", "later", {})], context)
    assert [d["tool_id"] for d in decision["decisions"]] == ["old", "new"]
    assert "calls" not in context  # the caller's dict is not modified


# ---- unit behaviour of the shared helpers ---------------------------------------------


def test_tool_choice_without_tools_is_not_sent() -> None:
    request = GavioRequest(
        messages=[{"role": "user", "content": "hi"}],
        model="m",
        provider="mock",
        tool_choice="required",
    )
    for module in (openai, anthropic, gemini, ollama):
        payload = module.to_provider_payload(request)
        assert not {"tools", "tool_choice", "toolConfig"} & payload.keys(), module.__name__


def test_invalid_tool_choice_and_declarations_are_rejected() -> None:
    base = {"messages": [{"role": "user", "content": "hi"}], "model": "m", "provider": "mock"}
    tools = [{"name": "t"}]
    for kwargs in (
        {"tools": tools, "tool_choice": "sometimes"},
        {"tools": tools, "tool_choice": {"x": 1}},
        {"tools": [{"description": "no name"}]},
    ):
        with pytest.raises(ConfigurationError):
            openai.to_provider_payload(GavioRequest(**base, **kwargs))


def test_snake_case_input_schema_is_accepted_and_governance_fields_are_not_sent() -> None:
    request = GavioRequest(
        messages=[{"role": "user", "content": "hi"}],
        model="m",
        provider="mock",
        tools=[
            {
                "name": "t",
                "input_schema": {"type": "object", "properties": {"a": {"type": "string"}}},
                "permissions": ["x"],
                "risk": "destructive",
                "requires_approval": True,
            }
        ],
    )
    function = openai.to_provider_payload(request)["tools"][0]["function"]
    assert function == {
        "name": "t",
        "parameters": {"type": "object", "properties": {"a": {"type": "string"}}},
    }


def test_arguments_serialisation_is_compact_and_keeps_insertion_order() -> None:
    request = _history({"z": 1, "a": {"y": "é", "b": [1, 2]}})
    payload = openai.to_provider_payload(request)
    arguments = payload["messages"][1]["tool_calls"][0]["function"]["arguments"]
    assert arguments == '{"z":1,"a":{"y":"é","b":[1,2]}}'


def test_openai_assistant_turn_with_only_tool_calls_sends_null_content() -> None:
    payload = openai.to_provider_payload(_history({"iban": "x"}))
    assert payload["messages"][1]["content"] is None


def test_none_content_does_not_break_openai_style_histories() -> None:
    request = GavioRequest(
        messages=[
            {"role": "user", "content": "hi"},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [{"id": "c", "name": "t", "arguments": {}}],
            },
            {"role": "tool", "tool_call_id": "c", "content": "ok"},
        ],
        model="m",
        provider="mock",
    )
    assert request.prompt_text() == "hi\n\nt({})\nok"
    for module in (openai, anthropic, gemini, ollama):
        module.to_provider_payload(request)


async def test_none_content_survives_pii_guard() -> None:
    request = GavioRequest(
        messages=[
            {"role": "user", "content": "hi"},
            {
                "role": "assistant",
                "content": None,
                "tool_calls": [{"id": "c", "name": "t", "arguments": {}}],
            },
        ],
        model="m",
        provider="mock",
    )
    redacted = await PiiGuard().before(request, InterceptorContext(trace_id="t"))
    assert redacted.messages[1]["content"] is None


def test_adapter_classes_remain_provider_adapters() -> None:
    for _file, modules in _FILES.items():
        for _module, factory in modules:
            assert isinstance(factory(), ProviderAdapter)
