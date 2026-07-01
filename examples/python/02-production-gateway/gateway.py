"""Gavio production gateway — a realistic interceptor stack.

Shows the shape you'd run in production: audit (outermost) → PII guard →
timeout → retry, in front of a real provider. If no API key is set it falls
back to the mock provider so the example still runs end to end.

    pip install gavio
    export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY (optional)
    python gateway.py
"""

import asyncio
import os

from gavio import Gateway, Provider
from gavio.interceptors.audit import AuditInterceptor
from gavio.interceptors.pii import PiiGuard, Sensitivity
from gavio.interceptors.reliability import RetryInterceptor, TimeoutPolicy
from gavio.providers.mock import MockProvider


def build_gateway() -> Gateway:
    builder = (
        Gateway.builder()
        .use(AuditInterceptor(sink="stdout://"))          # outermost
        .use(PiiGuard(sensitivity=Sensitivity.STRICT))    # redact before egress
        .use(TimeoutPolicy(timeout_seconds=30))
        .use(RetryInterceptor(max_attempts=3, base_delay_ms=500))
    )

    if os.environ.get("ANTHROPIC_API_KEY"):
        return builder.provider(Provider.ANTHROPIC).model("claude-sonnet-4-6").build()
    if os.environ.get("OPENAI_API_KEY"):
        return builder.provider(Provider.OPENAI).model("gpt-4o").build()

    print("[info] No API key set — using MockProvider so the demo still runs.\n")
    return builder.adapter(MockProvider()).model("mock").build()


async def main() -> None:
    gw = build_gateway()

    resp = await gw.complete(
        messages=[
            {"role": "system", "content": "You are a concise billing assistant."},
            {"role": "user", "content": "Summarise the account for jan@example.com."},
        ],
        agent_id="billing-agent",
        session_id="sess-42",
    )

    print("\nReply       :", resp.content)
    print("Provider    :", resp.provider, resp.model_version)
    print("Interceptors:", resp.interceptors_fired)
    print(f"Tokens      : {resp.usage.total_tokens}   Cost: ${resp.cost_usd:.6f}")


if __name__ == "__main__":
    asyncio.run(main())
