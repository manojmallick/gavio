"""Loop guard (F-REL-09): the interceptor, its store, and the audit / inspector wiring.

The limit semantics themselves are pinned by the shared vectors in test_loop_guard_vectors.py.
"""

from __future__ import annotations

import pytest

from gavio import Gateway, GavioRequest, LoopGuardError
from gavio.context import InterceptorContext
from gavio.exceptions import ConfigurationError, ProviderUnavailableError
from gavio.interceptors.audit import AuditInterceptor, verify_chain
from gavio.interceptors.audit.record import AuditRecord
from gavio.interceptors.audit.sink import AuditSink
from gavio.interceptors.reliability import LoopGuard, LoopGuardPolicy, LoopGuardStore
from gavio.providers.mock import MockProvider
from gavio.response import GavioResponse
from gavio.types import Provider, ToolCall

MESSAGES = [{"role": "user", "content": "keep going"}]


class Scripted(MockProvider):
    """A mock provider with a price per call, optional tool calls, and optional failures."""

    def __init__(
        self,
        *,
        cost_usd: float = 0.0,
        tool_calls: list[ToolCall] | None = None,
        fail: bool = False,
    ) -> None:
        super().__init__(response="ok")
        self.cost_usd = cost_usd
        self.tool_calls = tool_calls or []
        self.fail = fail
        self.models: list[str] = []

    async def complete(self, request: GavioRequest) -> GavioResponse:
        self.models.append(request.model)
        if self.fail:
            raise ProviderUnavailableError("down")
        response = await super().complete(request)
        response.cost_usd = self.cost_usd
        response.tool_calls = list(self.tool_calls)
        return response


class CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def _gateway(provider: MockProvider, guard: LoopGuard, *, sink: AuditSink | None = None) -> Gateway:
    builder = Gateway.builder().adapter(provider).model("mock")
    if sink is not None:
        builder = builder.use(AuditInterceptor(sink=sink))  # outermost
    return builder.use(guard).build()


async def _agent_loop(gateway: Gateway, calls: int) -> list[GavioResponse]:
    """An agent loop: the first call is the root, every later call names it as its parent."""
    responses = [await gateway.complete(MESSAGES)]
    for _ in range(calls - 1):
        responses.append(
            await gateway.complete(MESSAGES, parent_trace_id=responses[0].trace_id)
        )
    return responses


# ---- the demo from the roadmap: a runaway agent is stopped ------------------------------------


async def test_a_runaway_agent_is_stopped_at_25_steps() -> None:
    provider = Scripted()
    gateway = _gateway(provider, LoopGuard(LoopGuardPolicy(id="agent", max_steps=25)))

    with pytest.raises(LoopGuardError) as stopped:
        await _agent_loop(gateway, 40)

    assert len(provider.models) == 25  # the 26th call never reached the provider
    assert stopped.value.decision["breached"] == "max_steps"
    assert stopped.value.decision["steps"] == 25
    assert "max_steps reached" in str(stopped.value)


async def test_a_runaway_agent_is_stopped_by_a_cost_cap() -> None:
    provider = Scripted(cost_usd=0.4)
    gateway = _gateway(provider, LoopGuard(LoopGuardPolicy(id="agent", max_cost_usd=1.0)))

    with pytest.raises(LoopGuardError) as stopped:
        await _agent_loop(gateway, 40)

    # $0.40 x 3 = $1.20 crosses the $1.00 cap; the call that crosses it still happened.
    assert len(provider.models) == 3
    assert stopped.value.decision["breached"] == "max_cost"
    assert stopped.value.decision["costUsd"] == pytest.approx(1.2)


async def test_repeated_tool_calls_are_stopped_without_leaking_the_arguments() -> None:
    secret = "jan@example.com"
    provider = Scripted(tool_calls=[ToolCall(id="c", name="lookup", arguments={"email": secret})])
    gateway = _gateway(provider, LoopGuard(LoopGuardPolicy(id="agent", max_repeated_tool_calls=3)))

    with pytest.raises(LoopGuardError) as stopped:
        await _agent_loop(gateway, 10)

    assert len(provider.models) == 3
    assert stopped.value.decision["repeatedToolCall"]["name"] == "lookup"
    assert stopped.value.decision["repeatedToolCall"]["count"] == 3
    assert "'lookup'" in str(stopped.value)
    assert secret not in str(stopped.value)
    assert secret not in str(stopped.value.decision)


async def test_wall_time_is_measured_from_the_runs_first_call() -> None:
    clock = FakeClock()
    guard = LoopGuard(LoopGuardPolicy(id="agent", max_wall_time_seconds=30), clock=clock)
    gateway = _gateway(Scripted(), guard)

    first = await gateway.complete(MESSAGES)
    clock.now += 29.9
    await gateway.complete(MESSAGES, parent_trace_id=first.trace_id)
    clock.now += 0.1
    with pytest.raises(LoopGuardError) as stopped:
        await gateway.complete(MESSAGES, parent_trace_id=first.trace_id)
    assert stopped.value.decision["breached"] == "max_wall_time"


async def test_a_loop_of_failing_calls_still_counts_its_steps() -> None:
    provider = Scripted(fail=True)
    gateway = _gateway(provider, LoopGuard(LoopGuardPolicy(id="agent", max_steps=3)))

    for _ in range(3):
        with pytest.raises(ProviderUnavailableError):
            await gateway.complete(MESSAGES, metadata={"runId": "r"})
    with pytest.raises(LoopGuardError):
        await gateway.complete(MESSAGES, metadata={"runId": "r"})
    assert len(provider.models) == 3


# ---- which calls belong to one run --------------------------------------------------------------


async def test_calls_with_an_explicit_run_id_share_a_run_and_other_runs_are_independent() -> None:
    provider = Scripted()
    gateway = _gateway(provider, LoopGuard(LoopGuardPolicy(id="agent", max_steps=2)))

    await gateway.complete(MESSAGES, metadata={"runId": "a"})
    await gateway.complete(MESSAGES, metadata={"runId": "a"})
    await gateway.complete(MESSAGES, metadata={"runId": "b"})  # a fresh run
    with pytest.raises(LoopGuardError):
        await gateway.complete(MESSAGES, metadata={"run_id": "a"})  # snake_case is accepted too


async def test_sub_agents_under_one_root_are_counted_together() -> None:
    gateway = _gateway(
        Scripted(), LoopGuard(LoopGuardPolicy(id="agent", max_steps=3))
    )
    root = await gateway.complete(MESSAGES, agent_id="orchestrator")
    await gateway.complete(MESSAGES, agent_id="agent-a", parent_trace_id=root.trace_id)
    await gateway.complete(MESSAGES, agent_id="agent-b", parent_trace_id=root.trace_id)
    with pytest.raises(LoopGuardError):
        await gateway.complete(MESSAGES, agent_id="agent-a", parent_trace_id=root.trace_id)


async def test_calls_without_a_run_id_or_parent_are_separate_runs() -> None:
    provider = Scripted()
    gateway = _gateway(provider, LoopGuard(LoopGuardPolicy(id="agent", max_steps=1)))
    for _ in range(5):
        await gateway.complete(MESSAGES)  # each is its own run, so none breaches
    assert len(provider.models) == 5


# ---- actions ----------------------------------------------------------------------------------


async def test_fallback_reroutes_to_the_fallback_model() -> None:
    provider = Scripted()
    guard = LoopGuard(
        LoopGuardPolicy(id="agent", max_steps=2, action="fallback", fallback_model="mock-mini")
    )
    gateway = _gateway(provider, guard)

    responses = await _agent_loop(gateway, 4)

    assert provider.models == ["mock", "mock", "mock-mini", "mock-mini"]
    assert [r.model for r in responses] == ["mock", "mock", "mock-mini", "mock-mini"]


async def test_dry_run_action_records_the_breach_and_lets_every_call_through() -> None:
    sink = CollectingSink()
    provider = Scripted()
    guard = LoopGuard(LoopGuardPolicy(id="agent", max_steps=2, action="dry_run"))
    gateway = _gateway(provider, guard, sink=sink)

    await _agent_loop(gateway, 4)

    assert len(provider.models) == 4
    verdicts = [r.loop_guard for r in sink.records]
    assert [v["action"] for v in verdicts] == ["allow", "allow", "dry_run", "dry_run"]
    assert [v["breached"] for v in verdicts] == [None, None, "max_steps", "max_steps"]


async def test_a_gateway_dry_run_never_stops_or_reroutes() -> None:
    sink = CollectingSink()
    provider = Scripted()
    guard = LoopGuard(LoopGuardPolicy(id="agent", max_steps=1))
    gateway = (
        Gateway.builder()
        .adapter(provider)
        .model("mock")
        .dry_run(True)
        .use(AuditInterceptor(sink=sink))
        .use(guard)
        .build()
    )

    await _agent_loop(gateway, 3)

    assert len(provider.models) == 3
    assert [r.loop_guard["action"] for r in sink.records] == ["allow", "dry_run", "dry_run"]


# ---- audit, inspector, governance ---------------------------------------------------------------


async def test_the_audit_record_carries_counts_only() -> None:
    sink = CollectingSink()
    secret = "jan@example.com"
    provider = Scripted(
        cost_usd=0.25, tool_calls=[ToolCall(id="c", name="lookup", arguments={"e": secret})]
    )
    gateway = _gateway(
        provider, LoopGuard(LoopGuardPolicy(id="agent", max_steps=10)), sink=sink
    )

    await _agent_loop(gateway, 3)

    third = sink.records[2].loop_guard
    assert third == {
        "policy_id": "agent",
        "run_id": sink.records[0].trace_id,
        "action": "allow",
        "breached": None,
        "steps": 2,
        "cost_usd": 0.5,
        "elapsed_seconds": third["elapsed_seconds"],
    }
    assert "loop_guard" in sink.records[2].to_dict()
    assert secret not in sink.records[2].to_json()


async def test_the_audit_record_has_no_loop_guard_key_without_a_guard() -> None:
    sink = CollectingSink()
    gateway = Gateway.builder().adapter(Scripted()).model("mock").use(
        AuditInterceptor(sink=sink, hash_chain=True)
    ).build()
    await gateway.complete(MESSAGES)
    await gateway.complete(MESSAGES)
    assert all("loop_guard" not in r.to_dict() for r in sink.records)
    assert verify_chain(sink.records)


async def test_hash_chain_still_verifies_with_a_guard() -> None:
    sink = CollectingSink()
    gateway = (
        Gateway.builder()
        .adapter(Scripted())
        .model("mock")
        .use(AuditInterceptor(sink=sink, hash_chain=True))
        .use(LoopGuard(LoopGuardPolicy(id="agent", max_steps=10)))
        .build()
    )
    await _agent_loop(gateway, 3)
    assert verify_chain(sink.records)
    tampered = [AuditRecord(**{**r.__dict__}) for r in sink.records]
    tampered[1].loop_guard = {**tampered[1].loop_guard, "steps": 99}
    assert not verify_chain(tampered)


async def test_a_breach_is_reported_to_the_inspector_and_as_a_governance_event() -> None:
    guard = LoopGuard(LoopGuardPolicy(id="agent", max_steps=1, action="dry_run"))
    request = GavioRequest(messages=MESSAGES, model="mock", provider=Provider.MOCK)
    ctx = InterceptorContext(trace_id=request.trace_id)

    await guard.before(request, ctx)  # step 1: within limits
    assert ctx.loop_guard is not None and ctx.loop_guard["breached"] is None
    assert ctx.drain_governance() == []

    again = GavioRequest(
        messages=MESSAGES, model="mock", provider=Provider.MOCK, parent_trace_id=request.trace_id
    )
    ctx2 = InterceptorContext(trace_id=again.trace_id)
    await guard.before(again, ctx2)
    assert ctx2.inspect_pending["loop_guard"]["breached"] == "max_steps"
    events = ctx2.drain_governance()
    assert [e["kind"] for e in events] == ["loop_guard"]
    assert events[0]["policyId"] == "agent"
    assert "loop_guard" in ctx2.interceptors_fired


# ---- the store ---------------------------------------------------------------------------------


def test_the_store_evicts_the_least_recently_used_run() -> None:
    store = LoopGuardStore(max_runs=2, clock=FakeClock())
    a = store.acquire("a")
    store.acquire("b")
    store.acquire("a")  # refresh a
    store.acquire("c")  # evicts b
    assert len(store) == 2
    assert store.get("b") is None
    assert store.get("a") is a


def test_the_store_forgets_idle_runs() -> None:
    clock = FakeClock()
    store = LoopGuardStore(ttl_seconds=60, clock=clock)
    first = store.acquire("a")
    first.start_call()
    clock.now += 59
    assert store.get("a") is first  # reading refreshes it
    clock.now += 59
    assert store.get("a") is first
    clock.now += 61
    assert store.get("a") is None
    restarted = store.acquire("a")
    assert restarted is not first
    assert restarted.steps == 0
    assert restarted.started_at == clock.now


def test_the_store_validates_its_bounds() -> None:
    for bad in (0, -1, True):
        with pytest.raises(ConfigurationError):
            LoopGuardStore(max_runs=bad)  # type: ignore[arg-type]
    with pytest.raises(ConfigurationError):
        LoopGuardStore(ttl_seconds=0)


async def test_a_run_that_expires_mid_call_does_not_break_the_response() -> None:
    clock = FakeClock()
    store = LoopGuardStore(ttl_seconds=10, clock=clock)
    guard = LoopGuard(LoopGuardPolicy(id="agent", max_steps=5), store=store, clock=clock)
    request = GavioRequest(messages=MESSAGES, model="mock", provider=Provider.MOCK)
    ctx = InterceptorContext(trace_id=request.trace_id)
    await guard.before(request, ctx)
    clock.now += 100  # the run idles out while the provider call is in flight
    response = GavioResponse(trace_id=request.trace_id, content="x", model="mock", provider="mock")
    assert await guard.after(response, ctx) is response


def test_loop_guard_accepts_a_policy_dict_and_rejects_a_bad_one() -> None:
    assert LoopGuard({"id": "p", "maxSteps": 3}).policy.max_steps == 3
    with pytest.raises(ConfigurationError):
        LoopGuard({"id": "p", "maxSteps": 0})
