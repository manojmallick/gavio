"""Token cost tracking (F-GOV-01).

Prices are USD per 1,000 tokens, sourced from public provider pricing and
overridable via config. Unknown models price at zero (logged once) rather than
guessing. Prices are intentionally data, not code — update the table, not the
estimator.
"""

from __future__ import annotations

import logging

from .types import TokenUsage

logger = logging.getLogger("gavio.pricing")


# model -> (input_per_1k_usd, output_per_1k_usd)
_DEFAULT_PRICES: dict[str, tuple[float, float]] = {
    # OpenAI
    "gpt-4o": (0.0025, 0.010),
    "gpt-4o-mini": (0.00015, 0.0006),
    "o1": (0.015, 0.060),
    "o1-mini": (0.0011, 0.0044),
    # Anthropic
    "claude-sonnet-4-6": (0.003, 0.015),
    "claude-sonnet-4-20250514": (0.003, 0.015),
    "claude-haiku-4-5": (0.0008, 0.004),
    "claude-opus-4-1": (0.015, 0.075),
    # Local / mock are free.
    "mock": (0.0, 0.0),
}


class PricingProvider:
    """Estimates request cost from token usage and a model price table."""

    def __init__(self, prices: dict[str, tuple[float, float]] | None = None) -> None:
        self._prices = dict(_DEFAULT_PRICES)
        if prices:
            self._prices.update(prices)
        self._warned: set[str] = set()

    def set_price(
        self, model: str, input_per_1k: float, output_per_1k: float
    ) -> None:
        self._prices[model] = (input_per_1k, output_per_1k)

    def rates(self, model: str) -> tuple[float, float]:
        rate = self._prices.get(model)
        if rate is None:
            # try a prefix match (e.g. "gpt-4o-2024-..." -> "gpt-4o")
            for known, value in self._prices.items():
                if model.startswith(known):
                    return value
            if model not in self._warned:
                logger.warning("no pricing for model %r; treating as free", model)
                self._warned.add(model)
            return (0.0, 0.0)
        return rate

    def estimate(self, model: str, usage: TokenUsage) -> float:
        in_rate, out_rate = self.rates(model)
        cost = (usage.prompt_tokens / 1000.0) * in_rate
        cost += (usage.completion_tokens / 1000.0) * out_rate
        return round(cost, 8)


def estimate_tokens(text: str) -> int:
    """Rough token estimate (~4 chars/token) for providers without a tokenizer."""
    if not text:
        return 0
    return max(1, len(text) // 4)
