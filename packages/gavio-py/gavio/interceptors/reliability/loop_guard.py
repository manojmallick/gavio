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

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from typing import Any

from ...exceptions import ConfigurationError
from ...types import ToolCall

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

    def record(self, cost_usd: float, tool_calls: Iterable[ToolCall] = ()) -> None:
        """Record one call that went through: allowed, fallback or dry_run, never a refused one."""
        self.steps += 1
        self.cost_usd = round8(self.cost_usd + max(cost_usd, 0.0))
        for call in tool_calls:
            key = (call.name, call.arguments_hash())
            self.tool_calls[key] = self.tool_calls.get(key, 0) + 1

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
