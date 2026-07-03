"""Inspector tests (F-DX-09 / F-DX-10) — event vectors, HTTP server, gating.

The vector cases come from //test-vectors/inspector/event-sequences.json, the
same file the other SDKs run. The vectors describe a "dev-mode gateway", but
dev mode auto-wires an AuditInterceptor whose events are not in the expected
sequences — so we build with an explicit MockProvider adapter instead and set
``unsafe_content_capture_ack`` to make full-mode capture legal.
"""

from __future__ import annotations

import io
import json
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from gavio import ConfigurationError, Gateway, GavioRequest, GavioResponse
from gavio.context import InterceptorContext
from gavio.exceptions import ProviderError
from gavio.inspector import InspectorConfig
from gavio.interceptors.audit import AuditInterceptor
from gavio.interceptors.audit.sinks.stdout import StdoutSink
from gavio.interceptors.base import Interceptor
from gavio.interceptors.pii import PiiGuard
from gavio.providers.mock import MockProvider

# repo_root/packages/gavio-py/tests/unit/test_inspector.py -> repo_root/test-vectors
_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"

_END_EVENT_TYPES = ("interceptor.before.end", "interceptor.after.end", "provider.call.end")

_INTERCEPTOR_FACTORIES = {
    "pii_guard": lambda: PiiGuard(),
    "audit": lambda: AuditInterceptor(sink=StdoutSink(stream=io.StringIO())),
}


class _FailingProvider(MockProvider):
    """MockProvider whose complete() always raises."""

    async def complete(self, request: GavioRequest) -> GavioResponse:
        raise ProviderError("mock provider failure")


def _load_cases() -> list[dict]:
    path = _VECTORS / "inspector" / "event-sequences.json"
    return json.loads(path.read_text())["cases"]


def _build_gateway(case: dict) -> Gateway:
    adapter = _FailingProvider() if case.get("requireError") else MockProvider()
    builder = Gateway.builder().adapter(adapter).model("mock")
    for name in case["interceptors"]:
        builder.use(_INTERCEPTOR_FACTORIES[name]())
    builder.inspect(
        InspectorConfig(
            mode=case["mode"],
            start_server=False,
            unsafe_content_capture_ack=True,
        )
    )
    return builder.build()


@pytest.mark.parametrize("case", _load_cases(), ids=lambda c: c["id"])
async def test_event_sequence_vectors(case: dict) -> None:
    gw = _build_gateway(case)
    events: list[dict] = []
    gw.inspector.bus.subscribe(events.append)

    messages = case["request"]["messages"]
    if case.get("requireError"):
        with pytest.raises(ProviderError):
            await gw.complete(messages=messages)
    else:
        await gw.complete(messages=messages)

    expected = case["expectedEvents"]
    actual_types = [e["type"] for e in events]
    assert actual_types == [e["type"] for e in expected], (
        f"{case['id']}: expected {[e['type'] for e in expected]}, got {actual_types}"
    )
    for event, exp in zip(events, expected, strict=True):
        assert event["schemaVersion"] == "1.0"
        assert event["traceId"] and event["eventId"]
        if "name" in exp:
            assert event["data"]["name"] == exp["name"]
        if "status" in exp:
            assert event["data"]["status"] == exp["status"]
        if exp.get("mutated"):
            assert event["data"]["mutated"] is True

    for key in case.get("forbiddenDataKeys", []):
        for event in events:
            assert key not in event["data"], (
                f"{case['id']}: forbidden key {key!r} in {event['type']} data"
            )

    assert [e["seq"] for e in events] == list(range(len(events)))
    assert all(e["tNs"] >= 0 for e in events)
    for event in events:
        if event["type"] in _END_EVENT_TYPES:
            assert event["data"]["durationUs"] >= 0


async def test_http_server_endpoints() -> None:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .inspect(InspectorConfig(mode="full", port=0, unsafe_content_capture_ack=True))
        .build()
    )
    try:
        resp = await gw.complete(messages=[{"role": "user", "content": "hello server"}])
        base = f"http://127.0.0.1:{gw.inspector.server.port}"

        with urllib.request.urlopen(f"{base}/api/health") as r:
            assert r.headers["X-Gavio-Inspector-Mode"] == "full"
            health = json.loads(r.read())
        assert health["status"] == "ok"
        assert health["sdk"] == "python"
        assert health["mode"] == "full"
        assert health["traces"] == 1
        assert health["drops"] == 0

        with urllib.request.urlopen(f"{base}/api/pipeline") as r:
            pipeline = json.loads(r.read())
        assert pipeline["provider"] == "mock"
        assert pipeline["lints"] == []

        with urllib.request.urlopen(f"{base}/api/traces") as r:
            traces = json.loads(r.read())["traces"]
        assert len(traces) == 1
        assert traces[0]["traceId"] == resp.trace_id
        assert traces[0]["status"] == "ok"

        with urllib.request.urlopen(f"{base}/api/traces/{resp.trace_id}") as r:
            trace = json.loads(r.read())
        assert trace["summary"]["traceId"] == resp.trace_id
        assert trace["events"]

        with pytest.raises(urllib.error.HTTPError) as excinfo:
            urllib.request.urlopen(f"{base}/api/traces/no-such-trace")
        assert excinfo.value.code == 404

        with urllib.request.urlopen(f"{base}/") as r:
            html = r.read().decode()
        assert "Gavio Inspector" in html
    finally:
        gw.inspector.stop()


async def test_http_server_auth_token() -> None:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .inspect(InspectorConfig(mode="metadata", port=0, auth_token="s3cret"))
        .build()
    )
    try:
        base = f"http://127.0.0.1:{gw.inspector.server.port}"
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            urllib.request.urlopen(f"{base}/api/health")
        assert excinfo.value.code == 401

        req = urllib.request.Request(
            f"{base}/api/health", headers={"Authorization": "Bearer s3cret"}
        )
        with urllib.request.urlopen(req) as r:
            assert r.status == 200
    finally:
        gw.inspector.stop()


def test_full_mode_outside_dev_mode_requires_ack() -> None:
    with pytest.raises(ConfigurationError):
        (
            Gateway.builder()
            .adapter(MockProvider())
            .inspect(InspectorConfig(mode="full", start_server=False))
            .build()
        )


def test_non_loopback_bind_requires_auth_token() -> None:
    with pytest.raises(ConfigurationError):
        (
            Gateway.builder()
            .adapter(MockProvider())
            .inspect(InspectorConfig(mode="metadata", bind="0.0.0.0", start_server=False))
            .build()
        )


async def test_metadata_mode_events_are_structurally_content_free() -> None:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .use(PiiGuard())
        .inspect(InspectorConfig(mode="metadata", start_server=False))
        .build()
    )
    events: list[dict] = []
    gw.inspector.bus.subscribe(events.append)
    await gw.complete(messages=[{"role": "user", "content": "mail jan@example.com"}])
    assert events
    for event in events:
        for key in ("messages", "content", "diff"):
            assert key not in event["data"]


class _InspectingInterceptor(Interceptor):
    """Calls ctx.inspect() so we can verify it is safe with and without inspection."""

    @property
    def name(self) -> str:
        return "inspecting"

    async def before(self, request: GavioRequest, ctx: InterceptorContext) -> GavioRequest:
        ctx.inspect("route", {"chosen": "mock"})
        return request


async def test_disabled_inspector_leaves_gateway_unchanged() -> None:
    plain = Gateway.builder().adapter(MockProvider()).model("mock").build()
    inspected_off = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .use(_InspectingInterceptor())
        .build()
    )
    assert plain.inspector is None
    assert inspected_off.inspector is None

    messages = [{"role": "user", "content": "same either way"}]
    baseline = await plain.complete(messages=messages)
    # ctx.inspect() must be a harmless no-op with the inspector disabled.
    response = await inspected_off.complete(messages=messages)
    assert response.content == baseline.content


async def test_ctx_inspect_surfaces_as_decision() -> None:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .use(_InspectingInterceptor())
        .inspect(InspectorConfig(mode="metadata", start_server=False))
        .build()
    )
    events: list[dict] = []
    gw.inspector.bus.subscribe(events.append)
    await gw.complete(messages=[{"role": "user", "content": "hi"}])
    end = next(e for e in events if e["type"] == "interceptor.before.end")
    assert end["data"]["decision"] == {"route": {"chosen": "mock"}}


async def test_ring_buffer_evicts_oldest_trace() -> None:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .inspect(InspectorConfig(mode="metadata", max_traces=2, start_server=False))
        .build()
    )
    trace_ids = []
    for i in range(3):
        resp = await gw.complete(messages=[{"role": "user", "content": f"call {i}"}])
        trace_ids.append(resp.trace_id)

    buffer = gw.inspector.buffer
    assert buffer.count() == 2
    assert buffer.get(trace_ids[0]) is None  # oldest evicted
    assert [s["traceId"] for s in buffer.summaries()] == trace_ids[1:]
