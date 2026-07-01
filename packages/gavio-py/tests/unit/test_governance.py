"""Tests for governance: budget (F-GOV-02), rate limit (F-GOV-03), RBAC (F-GOV-04)."""

from __future__ import annotations

import pytest

from gavio import Gateway
from gavio.exceptions import (
    BudgetExceededError,
    ModelNotAllowedError,
    RateLimitExceededError,
)
from gavio.interceptors.governance import CostControl, ModelPolicy, RateLimiter
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
