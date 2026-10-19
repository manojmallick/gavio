"""Gavio loop guard - stop a runaway agent (F-REL-09).

    pip install -r requirements.txt
    python runaway_agent.py

A planner agent that never decides it is done keeps calling the model. The loop guard
counts the calls of one root run and refuses the next one once a limit is reached: first
the 25-step cap, then a cost cap. Offline: the mock provider, no key, no network.
"""

import asyncio
import logging

from gavio import Gateway, LoopGuardError
from gavio.interceptors.reliability import LoopGuard, LoopGuardPolicy
from gavio.pricing import PricingProvider
from gavio.providers.mock import MockProvider

MODEL = "demo-agent"
# Both strings are 40 characters, about 10 tokens each. At $6 per 1,000 tokens every call
# costs $0.12, so the cost cap below is reached after a handful of calls.
PROMPT = "Plan the next step of the task, then act"
REPLY = "Step done; planning the next step now..."
PRICES = {MODEL: (6.0, 6.0)}


async def run_agent(
    title: str, policy: LoopGuardPolicy, expect_calls: int, expect_breach: str
) -> None:
    gateway = (
        Gateway.builder()
        .adapter(MockProvider(response=REPLY, pricing=PricingProvider(PRICES)))
        .model(MODEL)
        .use(LoopGuard(policy))
        .build()
    )
    messages = [{"role": "user", "content": PROMPT}]

    # The agent loop. The first call is the root of the run; every later call names it as
    # its parent_trace_id, so the guard counts all of them as one run.
    root = await gateway.complete(messages, agent_id="planner")
    calls = 1
    try:
        while True:  # a runaway agent: nothing in the loop ever decides to stop
            await gateway.complete(messages, agent_id="planner", parent_trace_id=root.trace_id)
            calls += 1
    except LoopGuardError as stop:
        decision = stop.decision  # metadata only: counts, never prompts or tool arguments

    print(f"== {title}")
    print(f"provider calls : {calls}")
    reason, action = decision["reason"], decision["action"]
    print(f"stop reason    : {decision['breached']} ({reason}, action {action})")
    print(f"run so far     : {decision['steps']} steps, ${decision['costUsd']:.2f}")
    print(f"same run       : {'yes' if decision['runId'] == root.trace_id else 'no'}")
    print()

    # The example doubles as a CI check: fail if the guard stopped the run anywhere else.
    assert (calls, decision["breached"]) == (expect_calls, expect_breach), (calls, decision)


async def main() -> None:
    # The guard also logs a warning on a breach; this example prints the decision instead.
    logging.getLogger("gavio.loop_guard").setLevel(logging.ERROR)

    await run_agent(
        "Step cap: at most 25 calls per run",
        LoopGuardPolicy(id="planner-steps", max_steps=25),
        expect_calls=25,
        expect_breach="max_steps",
    )
    await run_agent(
        "Cost cap: $1.00 per run (the 25-step cap is still set)",
        LoopGuardPolicy(id="planner-budget", max_steps=25, max_cost_usd=1.00),
        expect_calls=9,  # $0.12 a call: the 9th call crosses $1.00 and still happens
        expect_breach="max_cost",
    )


if __name__ == "__main__":
    asyncio.run(main())
