"""Gavio Inspector — trace a multi-agent flow in the web UI.

Dev mode + inspect(True) serves the Inspector at http://127.0.0.1:7411 while a
small orchestrator → researcher/writer flow runs through the gateway. No API
key, no network beyond localhost.

    pip install "gavio>=1.3.0"
    python inspector.py
"""

import asyncio

from gavio import Gateway
from gavio.interceptors.pii import PiiGuard


async def main() -> None:
    # Dev mode = MockProvider + stdout audit; inspect(True) = UI on port 7411.
    gw = Gateway.builder().dev_mode(True).inspect(True).use(PiiGuard()).build()

    session = "sess-inspector-demo"

    # Orchestrator call — the root of the agent DAG.
    plan = await gw.complete(
        messages=[{"role": "user", "content": "Plan a briefing for jan@example.com"}],
        agent_id="orchestrator",
        session_id=session,
    )

    # Two child agents — parent_trace_id links them under the orchestrator.
    for agent, task in [
        ("researcher", "Collect facts for the briefing (IBAN NL91ABNA0417164300 on file)"),
        ("writer", "Draft the briefing from the research notes"),
    ]:
        await gw.complete(
            messages=[{"role": "user", "content": task}],
            agent_id=agent,
            parent_trace_id=plan.trace_id,
            session_id=session,
        )

    print("\nInspector: http://127.0.0.1:7411")
    print("  · Traces   — waterfall per request: interceptor spans + the provider call")
    print("  · a trace  — the PII diff: original vs redacted, side by side")
    print("  · DAG      — orchestrator → researcher/writer graph with cost rollups")
    print("  · Sessions —", session, "with per-session totals")
    input("\nOpen the UI, then press Enter to exit... ")


if __name__ == "__main__":
    asyncio.run(main())
