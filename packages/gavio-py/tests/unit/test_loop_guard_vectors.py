"""Loop guard (F-REL-09): the pure policy / state / evaluation / root-run parts.

Runs ``test-vectors/loop-guard/decisions.json`` — the file every SDK runs. The interceptor that
wires these into a gateway is tested separately.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from gavio.exceptions import ConfigurationError
from gavio.interceptors.reliability.loop_guard import (
    BREACH_ORDER,
    LoopGuardPolicy,
    LoopGuardState,
    evaluate_loop_guard,
)
from gavio.root_run import RootRunResolver
from gavio.types import ToolCall

_ROOT = Path(__file__).resolve().parents[4]
_VECTORS = json.loads(
    (_ROOT / "test-vectors" / "loop-guard" / "decisions.json").read_text(encoding="utf-8")
)
_SPEC = _ROOT / "spec"


def _schema(name: str) -> dict[str, Any]:
    return json.loads((_SPEC / f"{name}.schema.json").read_text(encoding="utf-8"))


def _ids(case: dict[str, Any]) -> str:
    return case["id"]


@pytest.mark.parametrize("case", _VECTORS["decisionCases"], ids=_ids)
def test_decision_vectors(case: dict[str, Any]) -> None:
    wire_state = case["state"]
    decision = evaluate_loop_guard(
        LoopGuardPolicy.from_dict(case["policy"]),
        LoopGuardState.from_dict(wire_state),
        run_id="run-1",
        elapsed_seconds=wire_state["elapsedSeconds"],
    ).to_dict()

    for key, value in case["expected"].items():
        assert decision[key] == value, key
    for key in case.get("expectedAbsent", []):
        assert key not in decision, key
    assert decision["policyId"] == case["policy"]["id"]
    assert decision["runId"] == "run-1"


def _drive(case: dict[str, Any]) -> list[dict[str, Any]]:
    """Play a run: decide before each call, record the calls that went through."""
    policy = LoopGuardPolicy.from_dict(case["policy"])
    state = LoopGuardState()
    decisions = []
    for call in case["calls"]:
        if state.started_at is None:
            state.started_at = call["atSeconds"]
        decision = evaluate_loop_guard(
            policy,
            state,
            run_id="run-1",
            elapsed_seconds=call["atSeconds"] - state.started_at,
        )
        decisions.append(decision.to_dict())
        if decision.action != "stop":  # a refused call never happened
            state.record(
                call["costUsd"],
                [
                    ToolCall(id=f"c{index}", name=item["name"], arguments=item["arguments"])
                    for index, item in enumerate(call.get("toolCalls", []))
                ],
            )
    return decisions


@pytest.mark.parametrize("case", _VECTORS["runCases"], ids=_ids)
def test_run_vectors(case: dict[str, Any]) -> None:
    decisions = _drive(case)

    assert [d["action"] for d in decisions] == case["expectedActions"]
    assert [d["breached"] for d in decisions] == case["expectedBreached"]
    assert [d["steps"] for d in decisions] == case["expectedSteps"]
    assert [d["costUsd"] for d in decisions] == case["expectedCostUsd"]
    assert [d["elapsedSeconds"] for d in decisions] == case["expectedElapsedSeconds"]
    if "expectedTargetModels" in case:
        assert [d.get("targetModel") for d in decisions] == case["expectedTargetModels"]


@pytest.mark.parametrize("case", _VECTORS["rootRunCases"], ids=_ids)
def test_root_run_vectors(case: dict[str, Any]) -> None:
    resolver = RootRunResolver(case["capacity"]) if "capacity" in case else RootRunResolver()
    resolved = [
        resolver.resolve(call["traceId"], call.get("parentTraceId"), call.get("metadata"))
        for call in case["calls"]
    ]
    assert resolved == case["expectedRunIds"]


@pytest.mark.parametrize("case", _VECTORS["policyErrorCases"], ids=_ids)
def test_policy_error_vectors(case: dict[str, Any]) -> None:
    assert case["expectedError"] == "Configuration"
    with pytest.raises(ConfigurationError):
        LoopGuardPolicy.from_dict(case["policy"])


# ---- beyond the vectors -------------------------------------------------------------------


def test_every_vector_decision_and_policy_matches_its_schema_shape() -> None:
    decision_schema = _schema("LoopGuardDecision")
    allowed = set(decision_schema["properties"])
    required = set(decision_schema["required"])
    policy_schema = _schema("LoopGuardPolicy")
    for case in _VECTORS["decisionCases"]:
        decision = evaluate_loop_guard(
            case["policy"],
            LoopGuardState.from_dict(case["state"]),
            run_id="r",
            elapsed_seconds=case["state"]["elapsedSeconds"],
        ).to_dict()
        assert required <= set(decision) <= allowed, case["id"]
        assert set(case["policy"]) <= set(policy_schema["properties"]), case["id"]
        assert set(LoopGuardPolicy.from_dict(case["policy"]).to_dict()) <= set(
            policy_schema["properties"]
        )
    assert set(decision_schema["properties"]["breaches"]["items"]["enum"]) == set(BREACH_ORDER)
    assert set(policy_schema["required"]) == {"id", "action"}


def test_policy_round_trips_and_accepts_snake_case() -> None:
    policy = LoopGuardPolicy(
        id="p", action="fallback", max_steps=25, max_cost_usd=1.5,
        max_wall_time_seconds=60, max_repeated_tool_calls=3, fallback_model="m",
    )
    assert LoopGuardPolicy.from_dict(policy.to_dict()) == policy
    assert LoopGuardPolicy.from_dict({"id": "p", "max_steps": 2, "action": "stop"}).max_steps == 2
    # An integer-valued float (JSON 3.0) is an integer; a fraction is not.
    assert LoopGuardPolicy.from_dict({"id": "p", "maxSteps": 3.0}).max_steps == 3
    with pytest.raises(ConfigurationError):
        LoopGuardPolicy.from_dict({"id": "p", "maxSteps": True})


def test_state_records_tool_calls_by_hash_and_keeps_first_seen_order() -> None:
    state = LoopGuardState()
    first = ToolCall(id="a", name="search", arguments={"q": "x", "n": 1})
    other = ToolCall(id="b", name="read", arguments={"q": "x"})
    state.record(0.1, [first, other])
    state.record(0.2, [ToolCall(id="c", name="search", arguments={"n": 1, "q": "x"})])
    assert state.steps == 2
    assert state.cost_usd == pytest.approx(0.3)
    assert list(state.tool_calls.values()) == [2, 1]
    assert [name for name, _ in state.tool_calls] == ["search", "read"]
    # The arguments themselves are not kept, only their hash.
    hashes = {first.arguments_hash(), other.arguments_hash()}
    assert {h for _, h in state.tool_calls} == hashes


def test_root_run_resolver_is_bounded_and_validated() -> None:
    resolver = RootRunResolver(3)
    for index in range(10):
        resolver.resolve(f"t{index}")
    assert len(resolver) == 3
    for bad in (0, -1, True, 1.5):
        with pytest.raises(ConfigurationError):
            RootRunResolver(bad)  # type: ignore[arg-type]
