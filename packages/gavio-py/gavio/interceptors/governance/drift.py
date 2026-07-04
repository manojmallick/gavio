"""DriftMonitor (F-GOV-07) — alert when a provider's response distribution shifts.

A ``DriftDetector`` is fed one metric sample per request (latency, tokens, …);
the default ``StatisticalDriftDetector`` keeps a rolling-window baseline and
flags a sample that deviates beyond a z-score threshold. Alerts surface as
``governance.event`` inspector events (and in ``/api/stats``) and are logged.
"""

from __future__ import annotations

import logging
import math
import statistics
from collections import deque
from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable

from ...context import InterceptorContext
from ...response import GavioResponse
from ..base import Interceptor

logger = logging.getLogger("gavio.drift")


@dataclass
class DriftAlert:
    metric: str
    value: float
    baseline: dict[str, Any]  # {"mean": ..., "std": ..., "n": ...}
    z: float | None  # None when the baseline had zero variance
    threshold: float


@runtime_checkable
class DriftDetector(Protocol):
    """Pluggable drift detector: fed per-request samples, returns any alerts."""

    @property
    def name(self) -> str: ...

    def observe(self, sample: dict[str, float]) -> list[DriftAlert]: ...


class StatisticalDriftDetector:
    """Rolling-window z-score detector — the default ``DriftDetector``."""

    def __init__(
        self,
        window_size: int = 50,
        min_samples: int | None = None,
        threshold: float = 3.0,
    ) -> None:
        self.window_size = window_size
        self.min_samples = min_samples if min_samples is not None else window_size
        self.threshold = threshold
        self._windows: dict[str, deque[float]] = {}

    @property
    def name(self) -> str:
        return "statistical"

    def observe(self, sample: dict[str, float]) -> list[DriftAlert]:
        alerts: list[DriftAlert] = []
        for metric, value in sample.items():
            if not math.isfinite(value):
                continue
            window = self._windows.setdefault(metric, deque())
            if len(window) >= self.min_samples:
                mean = statistics.fmean(window)
                std = statistics.pstdev(window)
                baseline = {"mean": round(mean, 4), "std": round(std, 4), "n": len(window)}
                if std > 0:
                    z = (value - mean) / std
                    if abs(z) > self.threshold:
                        alerts.append(
                            DriftAlert(metric, value, baseline, round(z, 4), self.threshold)
                        )
                elif value != mean:
                    alerts.append(DriftAlert(metric, value, baseline, None, self.threshold))
            window.append(value)
            if len(window) > self.window_size:
                window.popleft()
        return alerts


class DriftMonitor(Interceptor):
    """Observe-only interceptor that flags response-distribution drift (F-GOV-07)."""

    def __init__(
        self,
        metrics: list[str] | None = None,
        detector: DriftDetector | None = None,
        window_size: int = 50,
        min_samples: int | None = None,
        threshold: float = 3.0,
    ) -> None:
        self.metrics = metrics if metrics is not None else ["latency_ms", "total_tokens"]
        self.detector = detector or StatisticalDriftDetector(window_size, min_samples, threshold)

    @property
    def name(self) -> str:
        return "drift_monitor"

    @property
    def dry_run_safe(self) -> bool:
        # Never let a dry run pollute the baseline.
        return False

    def _extract(self, response: GavioResponse, ctx: InterceptorContext) -> dict[str, float]:
        sample: dict[str, float] = {}
        for metric in self.metrics:
            if metric == "latency_ms":
                sample[metric] = response.latency_ms
            elif metric == "total_tokens":
                sample[metric] = response.usage.total_tokens
            elif metric == "cost_usd":
                sample[metric] = response.cost_usd
            elif metric == "risk_score" and ctx.risk_score is not None:
                sample[metric] = ctx.risk_score
        return sample

    async def after(self, response: GavioResponse, ctx: InterceptorContext) -> GavioResponse:
        for alert in self.detector.observe(self._extract(response, ctx)):
            ctx.record_governance_event(
                {
                    "kind": "drift",
                    "detector": self.detector.name,
                    "metric": alert.metric,
                    "value": alert.value,
                    "baseline": alert.baseline,
                    "z": alert.z,
                    "threshold": alert.threshold,
                }
            )
            logger.warning(
                "drift: %s=%s from baseline mean=%s std=%s (n=%s)",
                alert.metric,
                alert.value,
                alert.baseline["mean"],
                alert.baseline["std"],
                alert.baseline["n"],
            )
        return response
