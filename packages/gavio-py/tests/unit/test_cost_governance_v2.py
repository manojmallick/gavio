from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio import Gateway
from gavio.cli import main as cli_main
from gavio.exceptions import BudgetExceededError
from gavio.interceptors.governance import (
    BudgetPolicy,
    BudgetPolicyControl,
    InMemoryBudgetStore,
    build_cost_governance_report,
    evaluate_budget,
)
from gavio.providers.mock import MockProvider

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors" / "cost-governance"


def _load(name: str) -> list[dict]:
    return json.loads((_VECTORS / name).read_text(encoding="utf-8"))["cases"]


@pytest.mark.parametrize("case", _load("budget-decisions.json"), ids=lambda c: c["id"])
def test_budget_decision_vectors(case: dict) -> None:
    decision = evaluate_budget(
        BudgetPolicy.from_dict(case["policy"]),
        scope=case["scope"],
        current_spend_usd=case["currentSpendUsd"],
        request_cost_usd=case["requestCostUsd"],
    ).to_dict()

    expected = case["expected"]
    for key, value in expected.items():
        assert decision.get(key) == value
    assert decision["policyId"] == case["policy"]["id"]
    assert decision["scope"] == case["scope"]


@pytest.mark.parametrize("case", _load("cost-report.json"), ids=lambda c: c["id"])
def test_cost_governance_report_vectors(case: dict) -> None:
    report = build_cost_governance_report(
        case["summaries"],
        policies=[BudgetPolicy.from_dict(policy) for policy in case["policies"]],
        group_by=case["groupBy"],
        usage_elapsed_ratio=case["usageElapsedRatio"],
    )

    expected = case["expected"]
    for key, value in expected["total"].items():
        assert report["total"][key] == value
    for group, group_expected in expected["groups"].items():
        for key, value in group_expected.items():
            assert report["groups"][group][key] == value
    for idx, budget_expected in enumerate(expected["budgets"]):
        for key, value in budget_expected.items():
            assert report["budgets"][idx][key] == value


async def test_budget_policy_control_falls_back_from_store_state() -> None:
    policy = BudgetPolicy(
        id="tenant-total",
        scope_type="tenant",
        scope_value="acme",
        window="total",
        limit_usd=1.0,
        hard_limit_action="fallback",
        fallback_model="mock-mini",
    )
    store = InMemoryBudgetStore({"tenant:acme|total": 0.95})
    control = BudgetPolicyControl(policy, store=store, estimated_request_cost_usd=0.1)
    gateway = Gateway.builder().adapter(MockProvider()).model("mock").use(control).build()

    response = await gateway.complete(
        messages=[{"role": "user", "content": "hello"}],
        metadata={"costDimensions": {"tenant": "acme"}},
    )

    assert response.model == "mock-mini"
    assert store.get("tenant:acme|total") >= 0.95


async def test_budget_policy_control_blocks_when_policy_requires_block() -> None:
    policy = BudgetPolicy(
        id="tenant-total",
        scope_type="tenant",
        scope_value="acme",
        window="total",
        limit_usd=1.0,
        hard_limit_action="block",
    )
    store = InMemoryBudgetStore({"tenant:acme|total": 0.95})
    control = BudgetPolicyControl(policy, store=store, estimated_request_cost_usd=0.1)
    gateway = Gateway.builder().adapter(MockProvider()).model("mock").use(control).build()

    with pytest.raises(BudgetExceededError):
        await gateway.complete(
            messages=[{"role": "user", "content": "hello"}],
            metadata={"costDimensions": {"tenant": "acme"}},
        )


def test_cost_report_cli_reads_summary_jsonl(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    case = _load("cost-report.json")[0]
    audit = tmp_path / "summaries.jsonl"
    audit.write_text(
        "\n".join(json.dumps(summary) for summary in case["summaries"]) + "\n",
        encoding="utf-8",
    )
    policies = tmp_path / "policies.json"
    policies.write_text(json.dumps({"policies": case["policies"]}), encoding="utf-8")

    rc = cli_main(
        [
            "cost",
            "report",
            "--audit",
            str(audit),
            "--group-by",
            "tenant",
            "--budget-policy",
            str(policies),
            "--usage-elapsed-ratio",
            "0.5",
        ]
    )

    captured = capsys.readouterr()
    assert rc == 0
    report = json.loads(captured.out)
    assert report["groups"]["acme"]["budgetRemainingUsd"] == 0.038
    assert report["budgets"][1]["status"] == "soft_limit"
