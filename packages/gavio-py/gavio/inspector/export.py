"""Export a captured trace as a test case (F-DX-12).

Renders a trace from the ring buffer as either a shared ``test-vectors/``
JSON case or a GavioTestKit unit test (Python, Java, or TypeScript source).
Detected PII values are replaced with the repo's synthetic fixtures before
anything leaves the server, so real data never lands in a test file.
"""

from __future__ import annotations

import json
from typing import Any

from ..interceptors.pii.context import ScanContext
from ..interceptors.pii.scanners import default_scanners

EXPORT_FORMATS = ("test-vector", "testkit-py", "testkit-java", "testkit-js")

# Synthetic stand-ins per entity type — same fixtures used across test-vectors/.
SYNTHETIC_FIXTURES = {
    "EMAIL": "jan@example.com",
    "IBAN": "NL91ABNA0417164300",
    "BSN": "123456782",
    "CREDIT_CARD": "4111111111111111",
    "SSN": "078-05-1120",
    "PHONE": "+31612345678",
    "IP_ADDRESS": "192.0.2.1",
    "SECRET": "***",
}

_scanners = default_scanners()


def sanitize_text(text: str) -> str:
    """Replace every detected PII span with its synthetic fixture."""
    matches = []
    for scanner in _scanners:
        matches.extend(scanner.scan(text, ScanContext()))
    # Replace right-to-left so earlier offsets stay valid; skip overlaps.
    last_start = len(text) + 1
    for m in sorted(matches, key=lambda m: m.start, reverse=True):
        if m.end > last_start:
            continue
        replacement = SYNTHETIC_FIXTURES.get(m.entity_type, "***")
        text = text[: m.start] + replacement + text[m.end :]
        last_start = m.start
    return text


def sanitize_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {"role": m.get("role", ""), "content": sanitize_text(m.get("content", ""))}
        for m in messages
    ]


def export_trace(trace: dict[str, Any], format_: str) -> tuple[str, str]:
    """Render one assembled trace. Returns (content_type, body).

    Raises ValueError for an unknown format or a trace without captured
    messages (metadata-mode traces cannot be exported).
    """
    if format_ not in EXPORT_FORMATS:
        raise ValueError(f"format must be one of {EXPORT_FORMATS}")
    start = next((e for e in trace["events"] if e["type"] == "trace.start"), None)
    messages = (start or {}).get("data", {}).get("messages")
    if not messages:
        raise ValueError("trace has no captured messages (metadata mode traces cannot be exported)")
    messages = sanitize_messages(messages)
    summary = trace["summary"]
    if format_ == "test-vector":
        return "application/json", json.dumps(
            _test_vector(summary, trace["events"], messages), indent=2
        )
    return "text/plain; charset=utf-8", _testkit(format_, summary, messages)


def _test_vector(
    summary: dict[str, Any], events: list[dict[str, Any]], messages: list[dict[str, Any]]
) -> dict[str, Any]:
    expected = []
    for event in events:
        entry: dict[str, Any] = {"type": event["type"]}
        data = event.get("data", {})
        if event["type"].startswith("interceptor.") and data.get("name"):
            entry["name"] = data["name"]
        if "status" in data and event["type"] in ("provider.call.end", "trace.end"):
            entry["status"] = data["status"]
        expected.append(entry)
    mode = next(
        (e["data"]["mode"] for e in events if e["type"] == "trace.start" and "mode" in e["data"]),
        "full",
    )
    interceptors = [
        name for name in summary.get("interceptorsFired") or [] if not name.startswith("_")
    ]
    return {
        "id": f"exported-{summary['traceId'][:8]}",
        "mode": mode,
        "interceptors": interceptors,
        "request": {"messages": messages},
        "expectedEvents": expected,
    }


def _testkit(format_: str, summary: dict[str, Any], messages: list[dict[str, Any]]) -> str:
    fired = [n for n in summary.get("interceptorsFired") or [] if not n.startswith("_")]
    pii = "pii_guard" in fired
    audit = "audit" in fired
    other = [n for n in fired if n not in ("pii_guard", "audit")]
    trace_id = summary["traceId"]
    slug = trace_id[:8].replace("-", "")
    note = f"# also fired in the original trace: {', '.join(other)}\n    " if other else ""
    if format_ == "testkit-py":
        uses = []
        if pii:
            uses.append("PiiGuard()")
        if audit:
            uses.append("AuditInterceptor()")
        imports = ""
        if pii:
            imports += "from gavio.interceptors.pii import PiiGuard\n"
        if audit:
            imports += "from gavio.interceptors.audit import AuditInterceptor\n"
        assertion = "\n    assert kit.pii_detected()" if pii else ""
        return (
            f'"""Exported from the Gavio Inspector — trace {trace_id}."""\n'
            f"from gavio.testing import GavioTestKit\n{imports}\n\n"
            f"async def test_exported_trace_{slug}() -> None:\n"
            f"    {note}kit = GavioTestKit(interceptors=[{', '.join(uses)}])\n"
            f"    messages = {json.dumps(messages)}\n"
            f"    response = await kit.run(messages)\n"
            f"    assert response.content{assertion}\n"
        )
    if format_ == "testkit-js":
        uses = []
        if pii:
            uses.append("new PiiGuard()")
        if audit:
            uses.append("new AuditInterceptor()")
        imports = "import { GavioTestKit } from 'gavio/testing'\n"
        if pii:
            imports += "import { PiiGuard } from 'gavio/interceptors/pii'\n"
        if audit:
            imports += "import { AuditInterceptor } from 'gavio/interceptors/audit'\n"
        note_js = f"// also fired in the original trace: {', '.join(other)}\n  " if other else ""
        assertion = "\n  expect(result.piiDetected()).toBe(true)" if pii else ""
        return (
            f"// Exported from the Gavio Inspector — trace {trace_id}\n"
            f"import {{ expect, test }} from 'vitest'\n{imports}\n"
            f"test('exported trace {slug}', async () => {{\n"
            f"  {note_js}const kit = new GavioTestKit({{ interceptors: [{', '.join(uses)}] }})\n"
            f"  const messages = {json.dumps(messages)}\n"
            f"  const result = await kit.run({{ messages }})\n"
            f"  expect(result.response.content).toBeTruthy(){assertion}\n"
            f"}})\n"
        )
    # testkit-java
    builder_uses = "".join(
        f".interceptor({u})"
        for u in (["new PiiGuard()"] if pii else []) + (["new AuditInterceptor()"] if audit else [])
    )
    note_java = (
        f"// also fired in the original trace: {', '.join(other)}\n        " if other else ""
    )
    java_messages = ",\n                ".join(
        f"Message.of({json.dumps(m['role'])}, {json.dumps(m['content'])})" for m in messages
    )
    assertion = "\n        assertTrue(result.piiDetected(null));" if pii else ""
    return (
        f"// Exported from the Gavio Inspector — trace {trace_id}\n"
        "import static org.junit.jupiter.api.Assertions.*;\n\n"
        "import io.gavio.testing.GavioTestKit;\n"
        "import io.gavio.testing.GavioTestResult;\n"
        "import io.gavio.types.Message;\n"
        "import java.util.List;\n"
        "import org.junit.jupiter.api.Test;\n\n"
        f"class ExportedTrace{slug}Test {{\n\n"
        "    @Test\n"
        "    void exportedTrace() {\n"
        f"        {note_java}GavioTestKit kit = GavioTestKit.builder(){builder_uses}.build();\n"
        "        GavioTestResult result = kit.run(List.of(\n"
        f"                {java_messages})).join();\n"
        f"        assertNotNull(result.response().content());{assertion}\n"
        "    }\n"
        "}\n"
    )
