"""Tests for risk scoring (F-QUA-06)."""

from __future__ import annotations

from gavio import Gateway
from gavio.context import InterceptorContext
from gavio.interceptors.audit import AuditInterceptor, AuditRecord
from gavio.interceptors.audit.sink import AuditSink
from gavio.interceptors.base import Interceptor
from gavio.interceptors.quality import RiskScorer, RiskWeights
from gavio.request import GavioRequest


class CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


class SignalSeeder(Interceptor):
    """Seeds the context with the raw signals RiskScorer reads."""

    def __init__(self, pii_types=(), guardrail=None, injection=None) -> None:
        self._pii = list(pii_types)
        self._guardrail = guardrail
        self._injection = injection

    @property
    def name(self) -> str:
        return "seeder"

    async def before(self, request: GavioRequest, ctx: InterceptorContext) -> GavioRequest:
        if self._pii:
            ctx.record_pii(self._pii)
        ctx.guardrail_outcome = self._guardrail
        ctx.risk_score = self._injection
        return request


def test_score_zero_when_no_signals():
    assert RiskScorer().score(0, None, None) == 0.0


def test_score_weights_each_signal():
    scorer = RiskScorer()  # defaults 0.3 / 0.4 / 0.3, pii_saturation 4
    # 2 PII entities → pii_signal 0.5 → 0.3 * 0.5 = 0.15
    assert abs(scorer.score(2, None, None) - 0.15) < 1e-9
    # guardrail FAIL → 1.0 → 0.4
    assert abs(scorer.score(0, "FAIL", None) - 0.4) < 1e-9
    # guardrail HITL → 0.6 → 0.24
    assert abs(scorer.score(0, "HITL", None) - 0.24) < 1e-9
    # injection 0.5 → 0.3 * 0.5 = 0.15
    assert abs(scorer.score(0, None, 0.5) - 0.15) < 1e-9


def test_score_saturates_and_clamps():
    scorer = RiskScorer()
    # 10 PII (>saturation 4) → pii_signal 1.0; FAIL 1.0; injection 1.0 → 0.3+0.4+0.3 = 1.0
    assert scorer.score(10, "FAIL", 1.0) == 1.0
    # Never exceeds 1.0 even with an out-of-range injection score.
    assert scorer.score(10, "FAIL", 5.0) == 1.0


def test_custom_weights():
    scorer = RiskScorer(RiskWeights(pii=1.0, guardrail=0.0, injection=0.0, pii_saturation=2))
    assert scorer.score(1, "FAIL", 1.0) == 0.5  # only PII counts, 1/2 = 0.5


async def test_composite_written_to_audit():
    sink = CollectingSink()
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(AuditInterceptor(sink=sink))  # outermost → its after runs last
        .use(SignalSeeder(pii_types=["EMAIL", "IBAN"], guardrail="FAIL", injection=1.0))
        .use(RiskScorer())
        .build()
    )
    await gw.complete(messages=[{"role": "user", "content": "hi"}])

    # pii_signal 0.5 (2/4) → 0.15; guardrail 0.4; injection 0.3 → 0.85
    assert abs(sink.records[0].risk_score - 0.85) < 1e-9
