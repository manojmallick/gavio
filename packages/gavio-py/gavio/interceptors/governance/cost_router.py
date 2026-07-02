"""CostRouter (F-GOV-06) — auto-route simple prompts to a cheaper model."""

from __future__ import annotations

import re
from typing import Protocol, runtime_checkable

from ...context import InterceptorContext
from ...pricing import estimate_tokens
from ...request import GavioRequest
from ..base import Interceptor

_REASONING_KEYWORDS = frozenset(
    {
        "why", "because", "compare", "trade-off", "tradeoff", "explain",
        "analyze", "analyse", "evaluate", "design", "architecture", "review",
        "debug", "reasoning", "justify", "critique",
    }
)
_TOKEN = re.compile(r"[a-z0-9-]+")


@runtime_checkable
class ComplexityScorer(Protocol):
    """Scores prompt text in ``[0, 1]`` — higher means more complex."""

    def score(self, text: str) -> float: ...


class HeuristicComplexityScorer:
    """Zero-dependency default: prompt length + reasoning-keyword density."""

    def score(self, text: str) -> float:
        tokens = estimate_tokens(text)
        length_score = min(tokens / 200, 1.0) * 0.6
        words = set(_TOKEN.findall(text.lower()))
        keyword_hits = len(words & _REASONING_KEYWORDS)
        keyword_score = min(keyword_hits / 3, 1.0) * 0.4
        return min(length_score + keyword_score, 1.0)


class CostRouter(Interceptor):
    """Reroute a request to ``simple_model`` when its complexity score is low.

    Register early in the chain, before caching, so a rerouted request's cache
    key reflects the model it actually ran on. Register after ``ModelPolicy``
    if RBAC should gate on the caller's *requested* model, not the rerouted one.
    """

    def __init__(
        self,
        simple_model: str,
        complexity_threshold: float = 0.35,
        scorer: ComplexityScorer | None = None,
    ) -> None:
        self.simple_model = simple_model
        self.complexity_threshold = complexity_threshold
        self.scorer = scorer or HeuristicComplexityScorer()

    @property
    def name(self) -> str:
        return "cost_router"

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        score = self.scorer.score(request.prompt_text())
        rerouted = score < self.complexity_threshold and request.model != self.simple_model
        ctx.state["cost_router"] = {
            "rerouted": rerouted,
            "original_model": request.model,
            "complexity_score": score,
        }
        if not rerouted:
            return request
        result = request.copy_with_messages(request.messages)
        result.model = self.simple_model
        return result
