"""Gavio Runtime Export - metadata-safe JSONL event export."""

from __future__ import annotations

import asyncio
import io
import json

from gavio import Gateway, JsonlRuntimeExporter
from gavio.interceptors.pii import PiiGuard


async def main() -> None:
    stream = io.StringIO()
    gateway = (
        Gateway.builder()
        .dev_mode(True)
        .use(PiiGuard())
        .exporter(JsonlRuntimeExporter(stream=stream))
        .build()
    )

    await gateway.complete(
        messages=[{"role": "user", "content": "Email jan@example.com about ACME billing"}],
        metadata={"tenant": "acme", "feature": "support-chat", "environment": "dev"},
    )

    events = [json.loads(line) for line in stream.getvalue().splitlines()]
    content_keys = {"messages", "content", "diff"}
    leaked = [
        event["type"]
        for event in events
        if any(key in json.dumps(event["data"]) for key in content_keys)
    ]

    print(f"exported_events={len(events)}")
    print(f"event_types={[event['type'] for event in events]}")
    print(f"content_keys_exported={bool(leaked)}")


if __name__ == "__main__":
    asyncio.run(main())
