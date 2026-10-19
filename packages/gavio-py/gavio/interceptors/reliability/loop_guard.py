"""Loop guard policy, state and evaluation (F-REL-09).

A loop guard stops a runaway agent: it keeps a small state per root run
(:mod:`gavio.root_run`) and, before every provider call, compares that state with a
:class:`LoopGuardPolicy`. This module is the pure part — policy, decision, state and
:func:`evaluate_loop_guard` — with no clock, no I/O and no storage, so every SDK can pin it with
the shared vectors in ``test-vectors/loop-guard/decisions.json``. Semantics are in
``spec/README.md`` ('Loop guard'); the wire shape is camelCase like the schemas
(``spec/LoopGuardPolicy.schema.json``, ``spec/LoopGuardDecision.schema.json``), Python attributes
are snake_case.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import OrderedDict
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field, replace
from typing import Any

from ...context import InterceptorContext
from ...exceptions import ConfigurationError, LoopGuardError
from ...request import GavioRequest
from ...response import GavioResponse
from ...root_run import DEFAULT_MAX_ENTRIES, RootRunResolver
from ...types import ToolCall
from ..base import Interceptor

logger = logging.getLogger("gavio.loop_guard")

#: Limits in the order they are reported: ``breached`` is the first one reached.
BREACH_ORDER = ("max_steps", "max_cost", "max_wall_time", "repeated_tool_call")
ACTIONS = ("stop", "fallback", "dry_run")


def _integer(name: str, value: Any, minimum: int) -> int | None:
    if value is None:
        return None
    is_number = isinstance(value, int | float) and not isinstance(value, bool)
    if not is_number or not float(value).is_integer():
        raise ConfigurationError(f"{name} must be an integer")
    if value < minimum:
        raise ConfigurationError(f"{name} must be at least {minimum}")
    return int(value)


def _number(name: str, value: Any, *, minimum: float, exclusive: bool = False) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise ConfigurationError(f"{name} must be a number")
    if value < minimum or (exclusive and value == minimum):
        comparison = "greater than" if exclusive else "at least"
        raise ConfigurationError(f"{name} must be {comparison} {minimum:g}")
    return float(value)


@dataclass(frozen=True)
class LoopGuardPolicy:
    """Limits for one root run. Every limit is optional; ``None`` is never breached.

    A limit is what the run may consume: it is checked before a call and reached once it is
    consumed, so ``max_steps=25`` allows 25 calls and refuses the 26th.
    """

    id: str
    action: str = "stop"
    max_steps: int | None = None
    max_cost_usd: float | None = None
    max_wall_time_seconds: float | None = None
    max_repeated_tool_calls: int | None = None
    fallback_model: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not isinstance(self.id, str) or not self.id:
            raise ConfigurationError("loop guard policy id must be a non-empty string")
        if self.action not in ACTIONS:
            raise ConfigurationError(f"loop guard action must be one of {', '.join(ACTIONS)}")
        # Normalise integer-valued floats (JSON 3.0) and validate; frozen, so set via object.
        object.__setattr__(self, "max_steps", _integer("max_steps", self.max_steps, 1))
        object.__setattr__(
            self,
            "max_repeated_tool_calls",
            _integer("max_repeated_tool_calls", self.max_repeated_tool_calls, 2),
        )
        object.__setattr__(
            self, "max_cost_usd", _number("max_cost_usd", self.max_cost_usd, minimum=0.0)
        )
        object.__setattr__(
            self,
            "max_wall_time_seconds",
            _number(
                "max_wall_time_seconds", self.max_wall_time_seconds, minimum=0.0, exclusive=True
            ),
        )
        if self.action == "fallback" and not self.fallback_model:
            raise ConfigurationError("loop guard action 'fallback' needs a fallback_model")

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> LoopGuardPolicy:
        def pick(camel: str, snake: str) -> Any:
            return data.get(camel, data.get(snake))

        return cls(
            id=data.get("id"),  # type: ignore[arg-type]  # validated in __post_init__
            action=str(data.get("action", "stop")),
            max_steps=pick("maxSteps", "max_steps"),
            max_cost_usd=pick("maxCostUsd", "max_cost_usd"),
            max_wall_time_seconds=pick("maxWallTimeSeconds", "max_wall_time_seconds"),
            max_repeated_tool_calls=pick("maxRepeatedToolCalls", "max_repeated_tool_calls"),
            fallback_model=pick("fallbackModel", "fallback_model"),
            metadata=dict(data.get("metadata") or {}),
        )

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": self.id,
            "action": self.action,
            "metadata": dict(self.metadata),
        }
        for key, value in (
            ("maxSteps", self.max_steps),
            ("maxCostUsd", self.max_cost_usd),
            ("maxWallTimeSeconds", self.max_wall_time_seconds),
            ("maxRepeatedToolCalls", self.max_repeated_tool_calls),
            ("fallbackModel", self.fallback_model),
        ):
            if value is not None:
                out[key] = value
        return out


@dataclass(frozen=True)
class RepeatedToolCall:
    """A tool call the run keeps requesting: its name, arguments hash and request count."""

    name: str
    arguments_hash: str
    count: int

    def to_dict(self) -> dict[str, Any]:
        return {"name": self.name, "argumentsHash": self.arguments_hash, "count": self.count}


@dataclass(frozen=True)
class LoopGuardDecision:
    """The guard's verdict on one provider call. Metadata only: counts, a cost and a time."""

    policy_id: str
    run_id: str
    allowed: bool
    action: str
    breached: str | None
    breaches: tuple[str, ...]
    reason: str
    steps: int
    cost_usd: float
    elapsed_seconds: float
    repeated_tool_call: RepeatedToolCall | None = None
    target_model: str | None = None

    def to_dict(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "policyId": self.policy_id,
            "runId": self.run_id,
            "allowed": self.allowed,
            "action": self.action,
            "breached": self.breached,
            "breaches": list(self.breaches),
            "reason": self.reason,
            "steps": self.steps,
            "costUsd": self.cost_usd,
            "elapsedSeconds": self.elapsed_seconds,
            "repeatedToolCall": (
                self.repeated_tool_call.to_dict() if self.repeated_tool_call else None
            ),
        }
        if self.target_model is not None:
            out["targetModel"] = self.target_model
        return out


@dataclass
class LoopGuardState:
    """What the guard remembers about one root run.

    ``tool_calls`` counts requested tool calls by ``(name, arguments hash)`` in first-seen order;
    the hash is :meth:`gavio.types.ToolCall.arguments_hash`, so repeats are found without keeping
    the arguments. ``started_at`` is whatever clock the caller uses; this module never reads one.
    """

    steps: int = 0
    cost_usd: float = 0.0
    tool_calls: dict[tuple[str, str], int] = field(default_factory=dict)
    started_at: float | None = None

    def start_call(self) -> None:
        """Count a call the guard let through, as soon as it starts.

        Counting at the start means a loop of calls that fail at the provider still reaches
        ``max_steps``.
        """
        self.steps += 1

    def add_result(self, cost_usd: float, tool_calls: Iterable[ToolCall] = ()) -> None:
        """Add what a finished call cost and the tool calls its response asked for."""
        self.cost_usd = round8(self.cost_usd + max(cost_usd, 0.0))
        for call in tool_calls:
            key = (call.name, call.arguments_hash())
            self.tool_calls[key] = self.tool_calls.get(key, 0) + 1

    def record(self, cost_usd: float, tool_calls: Iterable[ToolCall] = ()) -> None:
        """Record one call that went through: allowed, fallback or dry_run, never a refused one."""
        self.start_call()
        self.add_result(cost_usd, tool_calls)

    @classmethod
    def from_dict(cls, wire: Mapping[str, Any]) -> LoopGuardState:
        """Read the state shape the shared vectors use (``elapsedSeconds`` is passed separately)."""
        return cls(
            steps=int(wire.get("steps", 0)),
            cost_usd=float(wire.get("costUsd", 0.0)),
            tool_calls={
                (item["name"], item["argumentsHash"]): int(item["count"])
                for item in wire.get("toolCalls", ())
            },
        )


def evaluate_loop_guard(
    policy: LoopGuardPolicy | Mapping[str, Any],
    state: LoopGuardState,
    *,
    run_id: str,
    elapsed_seconds: float = 0.0,
) -> LoopGuardDecision:
    """Compare a run's state with a policy before its next provider call.

    A limit is reached when ``steps >= max_steps``, ``cost >= max_cost_usd``,
    ``elapsed >= max_wall_time_seconds`` (cost and elapsed time compared after rounding to 8
    places) or any counted tool call has reached ``max_repeated_tool_calls``.
    """
    if not isinstance(policy, LoopGuardPolicy):
        policy = LoopGuardPolicy.from_dict(policy)
    cost = round8(max(state.cost_usd, 0.0))
    elapsed = round8(max(elapsed_seconds, 0.0))

    breaches: list[str] = []
    repeated: RepeatedToolCall | None = None
    if policy.max_steps is not None and state.steps >= policy.max_steps:
        breaches.append("max_steps")
    if policy.max_cost_usd is not None and cost >= policy.max_cost_usd:
        breaches.append("max_cost")
    if policy.max_wall_time_seconds is not None and elapsed >= policy.max_wall_time_seconds:
        breaches.append("max_wall_time")
    if policy.max_repeated_tool_calls is not None:
        for (name, arguments_hash), count in state.tool_calls.items():
            if count >= policy.max_repeated_tool_calls:
                repeated = RepeatedToolCall(name, arguments_hash, count)
                breaches.append("repeated_tool_call")
                break

    breached = breaches[0] if breaches else None
    action = "allow" if breached is None else policy.action
    return LoopGuardDecision(
        policy_id=policy.id,
        run_id=run_id,
        allowed=action != "stop",
        action=action,
        breached=breached,
        breaches=tuple(breaches),
        reason="within_limits" if breached is None else f"{breached}_reached",
        steps=state.steps,
        cost_usd=cost,
        elapsed_seconds=elapsed,
        repeated_tool_call=repeated,
        target_model=policy.fallback_model if action == "fallback" else None,
    )


def round8(value: float) -> float:
    return round(value, 8)


class LoopGuardStore:
    """Bounded, idle-expiring in-memory store of per-run state.

    Keeps at most ``max_runs`` runs (least recently used evicted first) and forgets a run that
    has been idle for ``ttl_seconds``, so a long-lived process cannot grow without bound. A run
    that expires starts again from zero if it comes back. Safe to share between threads.
    """

    def __init__(
        self,
        max_runs: int = DEFAULT_MAX_ENTRIES,
        ttl_seconds: float = 3600.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if not isinstance(max_runs, int) or isinstance(max_runs, bool) or max_runs < 1:
            raise ConfigurationError("max_runs must be an integer of at least 1")
        if ttl_seconds <= 0:
            raise ConfigurationError("ttl_seconds must be positive")
        self._max_runs = max_runs
        self._ttl = ttl_seconds
        self._clock = clock
        self._runs: OrderedDict[str, tuple[LoopGuardState, float]] = OrderedDict()
        self._lock = threading.Lock()

    def acquire(self, run_id: str) -> LoopGuardState:
        """The state of ``run_id``, created (and started now) if it is new or has expired."""
        now = self._clock()
        with self._lock:
            self._expire(now)
            entry = self._runs.get(run_id)
            state = entry[0] if entry is not None else LoopGuardState(started_at=now)
            self._runs[run_id] = (state, now)
            self._runs.move_to_end(run_id)
            while len(self._runs) > self._max_runs:
                self._runs.popitem(last=False)
            return state

    def get(self, run_id: str) -> LoopGuardState | None:
        """The state of ``run_id`` if it is still remembered; refreshes it."""
        now = self._clock()
        with self._lock:
            self._expire(now)
            entry = self._runs.get(run_id)
            if entry is None:
                return None
            self._runs[run_id] = (entry[0], now)
            self._runs.move_to_end(run_id)
            return entry[0]

    def _expire(self, now: float) -> None:
        # Entries are ordered by recency, so the idle ones are all at the front.
        while self._runs:
            oldest = next(iter(self._runs))
            if now - self._runs[oldest][1] < self._ttl:
                break
            del self._runs[oldest]

    def __len__(self) -> int:
        with self._lock:
            return len(self._runs)


_RUN_KEY = "loop_guard:run_id"


class LoopGuard(Interceptor):
    """Stop a runaway agent loop (F-REL-09).

    Before every provider call the guard resolves the request's root run
    (:class:`~gavio.root_run.RootRunResolver`: ``metadata["runId"]``, else the root of the
    ``parent_trace_id`` chain, else the trace id), compares the run's state with ``policy`` and
    acts on the verdict: ``stop`` raises :class:`~gavio.exceptions.LoopGuardError`, ``fallback``
    reroutes the call to ``policy.fallback_model``, ``dry_run`` only records the breach. After the
    call it adds the response's cost and tool calls to the run. The verdict is attached to the
    audit record (counts only), the Inspector, and, on a breach, a governance event.

    An agent loop must pass one of ``metadata["runId"]`` or ``parent_trace_id`` on its calls —
    calls with neither are separate runs, which no limit can link. In a gateway ``dry_run`` the
    guard still evaluates and records, but never stops or reroutes a call.

    Register it before the audit interceptor's ``after`` runs (audit outermost, as always) and
    anywhere in the ``before`` order; it keeps its state in memory, per instance.
    """

    def __init__(
        self,
        policy: LoopGuardPolicy | Mapping[str, Any],
        *,
        resolver: RootRunResolver | None = None,
        store: LoopGuardStore | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.policy = (
            policy if isinstance(policy, LoopGuardPolicy) else LoopGuardPolicy.from_dict(policy)
        )
        self.resolver = resolver or RootRunResolver()
        self.store = store or LoopGuardStore(clock=clock)
        self._clock = clock
        self._lock = threading.Lock()

    @property
    def name(self) -> str:
        return "loop_guard"

    async def before(self, request: GavioRequest, ctx: InterceptorContext) -> GavioRequest:
        ctx.mark_fired(self.name)
        run_id = self.resolver.resolve(request.trace_id, request.parent_trace_id, request.metadata)
        with self._lock:  # evaluate and count atomically, also across threads
            state = self.store.acquire(run_id)
            started_at = state.started_at if state.started_at is not None else self._clock()
            decision = evaluate_loop_guard(
                self.policy,
                state,
                run_id=run_id,
                elapsed_seconds=self._clock() - started_at,
            )
            if ctx.dry_run and decision.action in ("stop", "fallback"):
                # A gateway dry run observes; it never refuses or reroutes a call.
                decision = replace(decision, action="dry_run", allowed=True, target_model=None)
            if decision.allowed:
                state.start_call()
        wire = decision.to_dict()
        ctx.loop_guard = {
            "policy_id": decision.policy_id,
            "run_id": decision.run_id,
            "action": decision.action,
            "breached": decision.breached,
            "steps": decision.steps,
            "cost_usd": decision.cost_usd,
            "elapsed_seconds": decision.elapsed_seconds,
        }
        ctx.inspect("loop_guard", wire)
        if decision.breached is not None:
            logger.warning(
                "loop guard %s: %s reached for run %s (%s)",
                self.policy.id,
                decision.breached,
                run_id,
                decision.action,
            )
            ctx.record_governance_event(
                {"kind": "loop_guard", "decision": wire, "policyId": self.policy.id}
            )
        if decision.action == "stop":
            raise LoopGuardError(_stop_message(decision), wire)
        ctx.state[_RUN_KEY] = run_id
        if (
            decision.action == "fallback"
            and decision.target_model
            and request.model != decision.target_model
        ):
            return replace(request, model=decision.target_model)
        return request

    async def after(self, response: GavioResponse, ctx: InterceptorContext) -> GavioResponse:
        run_id = ctx.state.get(_RUN_KEY)
        if isinstance(run_id, str):
            state = self.store.get(run_id)
            if state is not None:  # None: the run expired or was evicted mid-call
                state.add_result(response.cost_usd, response.tool_calls)
        return response


def _stop_message(decision: LoopGuardDecision) -> str:
    detail = ""
    if decision.repeated_tool_call is not None:
        call = decision.repeated_tool_call
        detail = f" ({call.name!r} requested {call.count} times with the same arguments)"
    return (
        f"loop guard {decision.policy_id} stopped run {decision.run_id}: "
        f"{decision.breached} reached{detail} after {decision.steps} steps, "
        f"${decision.cost_usd:.4f}, {decision.elapsed_seconds:.1f}s"
    )
