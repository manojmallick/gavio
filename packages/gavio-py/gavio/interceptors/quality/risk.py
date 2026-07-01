"""RiskScorer (F-QUA-06) — a composite risk score from per-request signals.

Folds the signals other interceptors leave on the :class:`InterceptorContext`
— PII entities found, guardrail outcome, and the prompt-injection risk — into a
single score in ``[0, 1]`` written to ``ctx.risk_score`` (and thus the audit
record). Register it *inside* the audit interceptor so audit sees the composite.
"""

from __future__ import annotations

from dataclasses import dataclass

from ...context import InterceptorContext
from ...response import GavioResponse
from ..base import Interceptor

# Guardrail outcome → its contribution before weighting.
_GUARDRAIL_SIGNAL = {"FAIL": 1.0, "HITL": 0.6}


@dataclass(frozen=True)
class RiskWeights:
    """Weights for the composite. ``pii + guardrail + injection`` need not sum to
    1, but do by default so a maxed-out request scores 1.0."""

    pii: float = 0.3
    guardrail: float = 0.4
    injection: float = 0.3
    # PII entity count at which the PII signal saturates to 1.0 (<= 0 → any PII = 1.0).
    pii_saturation: int = 4


class RiskScorer(Interceptor):
    """Post-interceptor that writes a composite ``ctx.risk_score`` in ``[0, 1]``."""

    def __init__(self, weights: RiskWeights | None = None) -> None:
        self.weights = weights or RiskWeights()

    @property
    def name(self) -> str:
        return "risk_scorer"

    @property
    def dry_run_safe(self) -> bool:
        return True

    def score(
        self,
        pii_count: int,
        guardrail_outcome: str | None,
        injection_score: float | None,
    ) -> float:
        """Compute the composite risk score from the three raw signals."""
        w = self.weights
        if pii_count <= 0:
            pii_signal = 0.0
        elif w.pii_saturation <= 0:
            pii_signal = 1.0
        else:
            pii_signal = min(1.0, pii_count / w.pii_saturation)
        guardrail_signal = _GUARDRAIL_SIGNAL.get(guardrail_outcome or "", 0.0)
        injection_signal = injection_score or 0.0
        composite = (
            w.pii * pii_signal + w.guardrail * guardrail_signal + w.injection * injection_signal
        )
        return max(0.0, min(1.0, composite))

    async def after(self, response: GavioResponse, ctx: InterceptorContext) -> GavioResponse:
        pii_count = sum(ctx.pii_entity_counts.values())
        ctx.risk_score = self.score(pii_count, ctx.guardrail_outcome, ctx.risk_score)
        return response
