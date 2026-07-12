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
    integration_metadata,
)
from gavio.exporters import otel_spans_from_events
from gavio.interceptors.audit import AuditInterceptor, AuditRecord, AuditSink
from gavio.interceptors.pii import PiiGuard


class MemoryAuditSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


async def main() -> None:
    event_stream = io.StringIO()
    audit_sink = MemoryAuditSink()
    gateway = (
        Gateway.builder()
        .dev_mode(True)
        .use(AuditInterceptor(sink=audit_sink, hash_chain=True))
        .use(PiiGuard(log_entity_types=False))
        .exporter(JsonlRuntimeExporter(stream=event_stream))
        .build()
    )
    metadata = integration_metadata(
        "litellm",
        tenant="acme",
        feature="support-chat",
        environment="dev",
        workflow="gateway-otel-eval-audit",
    )

    response = await gateway.complete(
        messages=[{"role": "user", "content": "Reply to jan@example.com about invoice 42"}],
        metadata=metadata,
    )
    events = [json.loads(line) for line in event_stream.getvalue().splitlines()]
    spans = otel_spans_from_events(events, service_name="gavio-integrations")

    registry = PromptRegistry(
        [
            PromptTemplate(
                id="integration.reply",
                version="2026-07-12",
                messages=[{"role": "user", "content": "Summarize {{ topic }}"}],
                required_variables=("topic",),
            )
        ]
    )
    suite = EvalSuite.from_dict(
        {
            "id": "integration-smoke",
            "cases": [
                {
                    "id": "mock-reply-present",
                    "templateId": "integration.reply",
                    "variables": {"topic": "support reply"},
                    "assertions": [{"type": "contains", "value": "[mock reply]"}],
                }
            ],
        }
    )
    report = await suite.run(registry, lambda _prompt, _case: response.content)

    evidence: dict[str, Any] = {
        "gateway": metadata["gateway"],
        "integration": metadata["integration"],
        "eventTypes": [event["type"] for event in events],
        "spanNames": [span["name"] for span in spans],
        "evalScore": report.score,
        "auditRecords": len(audit_sink.records),
        "auditReplay": [
            {
                "traceId": record.trace_id,
                "promptHash": record.prompt_hash,
                "responseHash": record.response_hash,
                "previousHash": record.previous_hash,
            }
            for record in audit_sink.records
        ],
        "rawContentExported": any(
            key in json.dumps(event.get("data", {}))
            for event in events
            for key in ("messages", "content", "diff")
        ),
    }
    print(json.dumps(evidence, indent=2, sort_keys=True))


if __name__ == "__main__":
    asyncio.run(main())
