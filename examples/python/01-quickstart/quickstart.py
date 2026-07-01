"""Gavio quickstart — PII redaction in dev mode.

Runs with zero setup: no API key, no network. Dev mode wires a mock provider
and a stdout audit sink automatically, so you can watch the whole pipeline.

    pip install gavio
    python quickstart.py
"""

import asyncio

from gavio import Gateway
from gavio.interceptors.pii import PiiGuard


async def main() -> None:
    # Dev mode = MockProvider + stdout audit, all in-process.
    gw = Gateway.builder().dev_mode(True).use(PiiGuard()).build()

    resp = await gw.complete(
        messages=[
            {
                "role": "user",
                "content": "Email jan@example.com about IBAN NL91ABNA0417164300",
            }
        ],
        agent_id="quickstart",
    )

    # The email + IBAN were redacted before the (mock) provider saw them,
    # then restored in the reply.
    print("\nReply    :", resp.content)
    print("PII found:", resp.audit.pii_entity_types)
    print("Fired    :", resp.interceptors_fired)
    print(f"Cost     : ${resp.cost_usd:.6f}   (mock = free)")
    print("Trace    :", resp.trace_id)


if __name__ == "__main__":
    asyncio.run(main())
