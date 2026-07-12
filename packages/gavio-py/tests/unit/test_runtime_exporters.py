from __future__ import annotations

import io
import json
from pathlib import Path

from gavio import Gateway
from gavio.exporters import JsonlRuntimeExporter, metadata_only_event
from gavio.inspector import InspectorConfig
from gavio.interceptors.pii import PiiGuard
from gavio.providers.mock import MockProvider

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


def _load_export_vector() -> dict:
    return json.loads((_VECTORS / "runtime-events" / "export-redaction.json").read_text())


def test_metadata_only_event_strips_content_vector() -> None:
    vector = _load_export_vector()
    redacted = metadata_only_event(vector["event"])

    assert redacted["data"] == vector["expectedData"]
    for key in vector["contentKeys"]:
        assert key not in json.dumps(redacted)


async def test_jsonl_exporter_auto_enables_metadata_events() -> None:
    stream = io.StringIO()
    exporter = JsonlRuntimeExporter(stream=stream)
    gw = Gateway.builder().adapter(MockProvider()).model("mock").exporter(exporter).build()

    assert gw.inspector is not None
    assert gw.inspector.mode == "metadata"
    assert gw.inspector.server is None

    await gw.complete(messages=[{"role": "user", "content": "hello export"}])

    events = [json.loads(line) for line in stream.getvalue().splitlines()]
    assert [event["type"] for event in events] == [
        "trace.start",
        "provider.call.start",
        "provider.call.end",
        "trace.end",
    ]
    for event in events:
        for key in ("messages", "content", "diff"):
            assert key not in event["data"]


async def test_jsonl_exporter_strips_content_even_when_inspector_is_full() -> None:
    stream = io.StringIO()
    exporter = JsonlRuntimeExporter(stream=stream)
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .use(PiiGuard())
        .inspect(
            InspectorConfig(
                mode="full",
                start_server=False,
                unsafe_content_capture_ack=True,
            )
        )
        .exporter(exporter)
        .build()
    )

    inspector_events: list[dict] = []
    gw.inspector.bus.subscribe(inspector_events.append)
    await gw.complete(messages=[{"role": "user", "content": "mail jan@example.com"}])

    assert any("messages" in event["data"] for event in inspector_events)
    exported = [json.loads(line) for line in stream.getvalue().splitlines()]
    assert exported
    for event in exported:
        for key in ("messages", "content", "diff"):
            assert key not in json.dumps(event["data"])
