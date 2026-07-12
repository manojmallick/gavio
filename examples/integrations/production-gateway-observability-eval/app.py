from __future__ import annotations

import asyncio
import io
import json
from typing import Any

from gavio import (
    EvalSuite,
    Gateway,
    JsonlRuntimeExporter,
    PromptRegistry,
    PromptTemplate,
    build_production_trust_bundle,
    integration_adapter_payload,
    integration_metadata,
    verify_production_trust_bundle,
)
from gavio.exporters import otel_spans_from_events
from gavio.interceptors.audit import AuditInterceptor, AuditRecord, AuditSink
from gavio.interceptors.pii import PiiGuard


class MemoryAuditSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


async def build_evidence() -> dict[str, Any]:
    event_stream = io.StringIO()
    audit_sink = MemoryAuditSink()
    metadata = integration_metadata(
        "litellm",
        tenant="acme",
        feature="support-triage",
        environment="prod",
        workflow="gateway-observability-eval",
    )

    gateway = (
        Gateway.builder()
        .dev_mode(True)
        .use(AuditInterceptor(sink=audit_sink, hash_chain=True))
        .use(PiiGuard(log_entity_types=False))
        .exporter(JsonlRuntimeExporter(stream=event_stream))
        .build()
    )
    response = await gateway.complete(
        messages=[
            {
                "role": "user",
                "content": "Reply to jan@example.com about invoice 42.",
            }
        ],
        metadata=metadata,
    )

    events = [json.loads(line) for line in event_stream.getvalue().splitlines()]
    spans = otel_spans_from_events(events, service_name="gavio-ecosystem-trust")
    source_event = next(event for event in reversed(events) if event["type"] == "trace.end")

    adapter_payloads = {
        integration_id: integration_adapter_payload(
            integration_id,
            source_event,
            metadata={**metadata, "prompt": "customer raw prompt"},
        )
        for integration_id in ("litellm", "promptfoo", "langfuse", "openlit")
    }

    registry = PromptRegistry(
        [
            PromptTemplate(
                id="support.reply",
                version="2.7.0",
                messages=[{"role": "user", "content": "Summarize {{ topic }}"}],
                required_variables=("topic",),
            )
        ]
    )
    suite = EvalSuite.from_dict(
        {
            "id": "ecosystem-trust-gateway",
            "cases": [
                {
                    "id": "mock-reply-present",
                    "templateId": "support.reply",
                    "templateVersion": "2.7.0",
                    "variables": {"topic": "support triage"},
                    "assertions": [{"type": "contains", "value": "[mock reply]"}],
                }
            ],
        }
    )
    report = await suite.run(registry, lambda _prompt, _case: response.content)

    trust_bundle = build_production_trust_bundle(
        bundle_id="ecosystem-trust-gateway-observability-eval",
        generated_at="2026-07-12T12:00:00Z",
        release={"version": "2.7.0", "channel": "example"},
        runtime={
            "environment": "production",
            "workflow": metadata["workflow"],
            "eventExportMode": "metadata_only",
            "integrations": sorted(adapter_payloads),
        },
        audit_records=audit_sink.records,
        runtime_events=events,
        controls=[
            {
                "type": "ecosystem_conformance",
                "id": "gateway-observability-eval",
                "status": "pass",
                "source": "test-vectors/integrations/ecosystem-trust.json",
            }
        ],
        documents=[
            {
                "name": "Ecosystem Trust Package",
                "path": "docs/integrations.md#ecosystem-trust-package",
            }
        ],
    )
    verification = verify_production_trust_bundle(trust_bundle)
    serialized_payloads = json.dumps(adapter_payloads, sort_keys=True)
    serialized_events = json.dumps(events, sort_keys=True)
    forbidden = ("customer raw prompt", "jan@example.com", "invoice 42")

    return {
        "app": "gateway-observability-eval",
        "integrationsCovered": sorted(adapter_payloads),
        "eventTypes": [event["type"] for event in events],
        "spanNames": [span["name"] for span in spans],
        "auditRecords": len(audit_sink.records),
        "evalPassed": report.failed_cases == 0,
        "evalScore": report.score,
        "trustBundleValid": verification.valid,
        "adapterTargets": {
            key: value["target"] for key, value in sorted(adapter_payloads.items())
        },
        "rawContentExported": any(
            value in serialized_payloads or value in serialized_events for value in forbidden
        ),
    }


def main() -> None:
    print(json.dumps(asyncio.run(build_evidence()), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
