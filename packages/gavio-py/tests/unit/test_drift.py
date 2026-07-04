"""Tests for drift detection (F-GOV-07)."""

from __future__ import annotations

from gavio import Gateway
from gavio.inspector import InspectorConfig
from gavio.inspector.analytics import build_stats
from gavio.interceptors.governance import DriftAlert, DriftMonitor, StatisticalDriftDetector
from gavio.providers.mock import MockProvider


def test_silent_while_baseline_fills() -> None:
    d = StatisticalDriftDetector(window_size=10, min_samples=10, threshold=3.0)
    for i in range(9):
        assert d.observe({"latency_ms": 100 + i}) == []


def test_flags_zscore_spike() -> None:
    d = StatisticalDriftDetector(window_size=20, min_samples=12, threshold=3.0)
    for i in range(12):
        d.observe({"latency_ms": 100 + (i % 5)})
    assert d.observe({"latency_ms": 105}) == []  # in-distribution → quiet
    alerts = d.observe({"latency_ms": 900})  # spike → drift
    assert len(alerts) == 1
    assert alerts[0].metric == "latency_ms"
    assert alerts[0].value == 900
    assert alerts[0].baseline["n"] >= 12
    assert abs(alerts[0].z) > 3


def test_zero_variance_baseline() -> None:
    d = StatisticalDriftDetector(window_size=10, min_samples=5, threshold=3.0)
    for _ in range(5):
        d.observe({"total_tokens": 42})
    alerts = d.observe({"total_tokens": 43})
    assert len(alerts) == 1
    assert alerts[0].z is None
    assert alerts[0].baseline["std"] == 0


def test_metrics_tracked_independently() -> None:
    d = StatisticalDriftDetector(window_size=10, min_samples=4, threshold=3.0)
    for i in range(4):
        d.observe({"latency_ms": 100 + (i % 3), "total_tokens": 50 + (i % 3)})
    alerts = d.observe({"latency_ms": 100, "total_tokens": 5000})
    assert [a.metric for a in alerts] == ["total_tokens"]


class _AlwaysDrift:
    name = "stub"

    def observe(self, sample: dict[str, float]) -> list[DriftAlert]:
        metric, value = next(iter(sample.items()))
        return [DriftAlert(metric, value, {"mean": 100.0, "std": 10.0, "n": 20}, 80.0, 3.0)]


class _Quiet:
    name = "quiet"

    def observe(self, sample: dict[str, float]) -> list[DriftAlert]:
        return []


def _gw(detector: object) -> Gateway:
    return (
        Gateway.builder()
        .adapter(MockProvider(response="ok"))
        .model("mock")
        .use(DriftMonitor(detector=detector, metrics=["latency_ms"]))
        .inspect(InspectorConfig(mode="metadata", start_server=False))
        .build()
    )


async def test_emits_governance_event_and_surfaces_in_stats() -> None:
    gw = _gw(_AlwaysDrift())
    events: list[dict] = []
    gw.inspector.bus.subscribe(events.append)

    await gw.complete(messages=[{"role": "user", "content": "a"}])
    await gw.complete(messages=[{"role": "user", "content": "b"}])

    governance = [e for e in events if e["type"] == "governance.event"]
    assert len(governance) == 2
    assert governance[0]["data"]["kind"] == "drift"
    assert governance[0]["data"]["metric"] == "latency_ms"

    stats = build_stats(gw.inspector.buffer.summaries())
    assert stats["total"]["driftAlerts"] == {"latency_ms": 2}


async def test_no_event_when_no_drift() -> None:
    gw = _gw(_Quiet())
    events: list[dict] = []
    gw.inspector.bus.subscribe(events.append)

    await gw.complete(messages=[{"role": "user", "content": "a"}])

    assert [e for e in events if e["type"] == "governance.event"] == []
    assert build_stats(gw.inspector.buffer.summaries())["total"]["driftAlerts"] == {}
