from __future__ import annotations

import json

from gavio import integration_adapter_payload


ADAPTERS = [
    "litellm",
    "promptfoo",
    "langfuse",
    "openlit",
    "langchain",
    "langgraph",
    "vercel-ai-sdk",
]


def main() -> None:
    event = {
        "traceId": "trace_adapter_tour",
        "type": "trace.end",
        "data": {
            "status": "ok",
            "latencyMs": 73,
            "costUsd": 0.0184,
            "provider": "openai",
            "model": "gpt-4o-mini",
            "content": "synthetic response text that should not appear in output",
        },
    }
    metadata = {
        "tenant": "acme",
        "feature": "support-chat",
        "environment": "dev",
        "workflow": "adapter-tour",
        "prompt": "synthetic prompt text that should be hashed",
    }
    forbidden = [event["data"]["content"], metadata["prompt"]]
    payloads = []

    for adapter in ADAPTERS:
        payload = integration_adapter_payload(adapter, event, metadata=metadata)
        serialized = json.dumps(payload, sort_keys=True)
        payloads.append(
            {
                "adapter": adapter,
                "kind": payload["kind"],
                "payloadKeys": sorted(payload["payload"].keys()),
                "containsRawContent": any(text in serialized for text in forbidden),
                "payload": payload,
            }
        )

    print(json.dumps({"adapterPayloads": payloads}, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
