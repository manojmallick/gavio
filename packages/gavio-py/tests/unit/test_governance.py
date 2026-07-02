"""Tests for governance: budget (F-GOV-02), rate limit (F-GOV-03), RBAC (F-GOV-04),
cost-optimiser routing (F-GOV-06)."""

from __future__ import annotations

import pytest

from gavio import Gateway
from gavio.exceptions import (
    BudgetExceededError,
    ModelNotAllowedError,
    RateLimitExceededError,
)
from gavio.interceptors.governance import (
    CostControl,
    CostRouter,
    HeuristicComplexityScorer,
    ModelPolicy,
    RateLimiter,
)
from gavio.pricing import PricingProvider
from gavio.providers.mock import MockProvider


def _gw(*interceptors, pricing=None):
    b = Gateway.builder().adapter(MockProvider(pricing=pricing)).model("mock")
    for i in interceptors:
        b = b.use(i)
    return b.build()


async def test_budget_blocks_after_hard_cap():
    # Force non-zero cost so the budget actually accrues.
    pricing = PricingProvider({"mock": (1000.0, 1000.0)})
    cost = CostControl(hard_cap_usd=0.01, scope="global", window="total")
    gw = _gw(cost, pricing=pricing)

    await gw.complete(messages=[{"role": "user", "content": "one"}])  # accrues cost
    with pytest.raises(BudgetExceededError):
        await gw.complete(messages=[{"role": "user", "content": "two"}])


async def test_budget_allows_under_cap():
    gw = _gw(CostControl(hard_cap_usd=100.0))  # mock cost is 0
    for _ in range(3):
        await gw.complete(messages=[{"role": "user", "content": "hi"}])


async def test_rate_limiter_requests_per_minute():
    gw = _gw(RateLimiter(max_requests_per_minute=2, scope="global"))
    await gw.complete(messages=[{"role": "user", "content": "1"}])
    await gw.complete(messages=[{"role": "user", "content": "2"}])
    with pytest.raises(RateLimitExceededError):
        await gw.complete(messages=[{"role": "user", "content": "3"}])


async def test_model_policy_rbac():
    policy = ModelPolicy(
        roles={"analyst": ["mock"], "guest": []},
    )
    gw = _gw(policy)
    # analyst allowed
    await gw.complete(
        messages=[{"role": "user", "content": "hi"}], metadata={"role": "analyst"}
    )
    # guest denied
    with pytest.raises(ModelNotAllowedError):
        await gw.complete(
            messages=[{"role": "user", "content": "hi"}], metadata={"role": "guest"}
        )


async def test_model_policy_wildcard():
    gw = _gw(ModelPolicy(roles={"admin": ["*"]}))
    await gw.complete(
        messages=[{"role": "user", "content": "hi"}], metadata={"role": "admin"}
    )


async def test_cost_router_reroutes_simple_prompt():
    gw = _gw(CostRouter(simple_model="mock-mini"))
    r = await gw.complete(messages=[{"role": "user", "content": "What is 2+2?"}])
    assert r.model == "mock-mini"


async def test_cost_router_skips_complex_prompt():
    gw = _gw(CostRouter(simple_model="mock-mini", complexity_threshold=0.35))
    r = await gw.complete(
        messages=[
            {
                "role": "user",
                "content": (
                    "Explain why the trade-off between consistency and "
                    "availability matters here, and compare it to the CAP "
                    "theorem, analyzing multiple failure scenarios in detail."
                ),
            }
        ]
    )
    assert r.model == "mock"


async def test_cost_router_skips_when_already_on_simple_model():
    gw = _gw(CostRouter(simple_model="mock"))
    r = await gw.complete(messages=[{"role": "user", "content": "hi"}])
    assert r.model == "mock"


async def test_cost_router_custom_scorer():
    class AlwaysComplex:
        def score(self, text: str) -> float:
            return 1.0

    gw = _gw(CostRouter(simple_model="mock-mini", scorer=AlwaysComplex()))
    r = await gw.complete(messages=[{"role": "user", "content": "What is 2+2?"}])
    assert r.model == "mock"


def test_heuristic_complexity_scorer():
    scorer = HeuristicComplexityScorer()
    simple = scorer.score("What is 2+2?")
    complex_ = scorer.score(
        "Explain why the trade-off between consistency and availability "
        "matters, and compare it to the CAP theorem across failure scenarios."
    )
    assert 0.0 <= simple <= 1.0
    assert 0.0 <= complex_ <= 1.0
    assert simple < complex_
