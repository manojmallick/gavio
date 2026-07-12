from __future__ import annotations

import json

from gavio import get_integration, integration_adapter_payload, integration_metadata


ADAPTER_INTEGRATIONS = {
    "litellm",
    "promptfoo",
    "langfuse",
    "openlit",
    "langchain",
    "langgraph",
    "vercel-ai-sdk",
}


def main(integration_id: str) -> None:
    recipe = get_integration(integration_id)
    metadata = integration_metadata(
        integration_id,
        tenant="acme",
        feature="support-chat",
        environment="dev",
        workflow="offline-smoke",
    )
    payload = {
        "id": recipe.id,
        "name": recipe.name,
        "category": recipe.category,
        "metadata": metadata,
        "gavioSurfaces": list(recipe.gavio_surfaces),
        "recommendedExporters": list(recipe.recommended_exporters),
        "docsPath": recipe.docs_path,
    }
    if integration_id in ADAPTER_INTEGRATIONS:
        payload["adapterPayload"] = integration_adapter_payload(
            integration_id,
            {
                "traceId": "trace_example",
                "type": "trace.end",
                "data": {
                    "status": "ok",
                    "latencyMs": 42,
                    "costUsd": 0.0042,
                    "provider": "openai",
                    "model": "gpt-4o-mini",
                    "content": "example response text that should not be emitted",
                },
            },
            metadata={**metadata, "prompt": "example prompt text that should be hashed"},
        )
    print(json.dumps(payload, indent=2, sort_keys=True))
