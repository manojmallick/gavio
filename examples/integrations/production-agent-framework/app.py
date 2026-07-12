from __future__ import annotations

import json
from typing import Any

from gavio import compatibility_matrix, integration_adapter_payload, integration_metadata


def _source_event(trace_id: str, route: str) -> dict[str, Any]:
    return {
        "schemaVersion": "1.0",
        "eventId": f"evt_{route}",
        "traceId": trace_id,
        "type": "trace.end",
        "seq": 9,
        "data": {
            "status": "ok",
            "latencyMs": 57,
            "costUsd": 0.018,
            "provider": "openai",
            "model": "gpt-4o-mini",
            "content": "raw model text",
        },
    }


def build_routes() -> list[dict[str, Any]]:
    rows = {row["id"]: row for row in compatibility_matrix()}
    routes = [
        {
            "id": "gateway-entry",
            "integrations": ["portkey", "helicone"],
            "metadata": integration_metadata(
                "portkey",
                tenant="acme",
                feature="agent-support",
                environment="prod",
                workflow="agent-framework-handoff",
            ),
        },
        {
            "id": "agent-router",
            "integrations": ["langchain", "langgraph"],
            "metadata": integration_metadata(
                "langchain",
                tenant="acme",
                feature="agent-support",
                environment="prod",
                workflow="agent-framework-handoff",
            ),
        },
        {
            "id": "streaming-response",
            "integrations": ["vercel-ai-sdk", "openai-sdk"],
            "metadata": integration_metadata(
                "vercel-ai-sdk",
                tenant="acme",
                feature="agent-support",
                environment="prod",
                workflow="agent-framework-handoff",
            ),
        },
    ]

    for route in routes:
        source = _source_event(f"trace_{route['id']}", route["id"])
        route["catalogRows"] = [rows[integration_id]["category"] for integration_id in route["integrations"]]
        route["adapterPayloads"] = {
            integration_id: integration_adapter_payload(
                integration_id,
                source,
                metadata={**route["metadata"], "prompt": "customer raw prompt"},
            )
            for integration_id in route["integrations"]
            if integration_id in {"langchain", "langgraph", "vercel-ai-sdk"}
        }
        route["metadataOnly"] = {
            integration_id: integration_metadata(
                integration_id,
                tenant="acme",
                feature="agent-support",
                environment="prod",
                workflow="agent-framework-handoff",
            )
            for integration_id in route["integrations"]
            if integration_id in {"portkey", "helicone", "openai-sdk"}
        }
    return routes


def main() -> None:
    routes = build_routes()
    serialized = json.dumps(routes, sort_keys=True)
    summary = {
        "app": "agent-framework-handoff",
        "routes": len(routes),
        "integrationsCovered": sorted(
            {integration_id for route in routes for integration_id in route["integrations"]}
        ),
        "adapterPayloads": sorted(
            {
                integration_id
                for route in routes
                for integration_id in route["adapterPayloads"].keys()
            }
        ),
        "metadataOnlyIntegrations": sorted(
            {
                integration_id
                for route in routes
                for integration_id in route["metadataOnly"].keys()
            }
        ),
        "metadataOnlyPayloads": True,
        "rawContentExported": any(
            value in serialized for value in ("customer raw prompt", "raw model text")
        ),
    }
    print(json.dumps(summary, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
