"""MetricsInterceptor (F-OBS-08) — records Prometheus metrics per request."""

from __future__ import annotations

from ...context import InterceptorContext
from ...response import GavioResponse
from ..base import Interceptor
from .registry import PrometheusMetrics


class MetricsInterceptor(Interceptor):
    """Post-interceptor that records per-request metrics into a registry.

    Holds the :class:`PrometheusMetrics` registry so callers can scrape it::

        metrics = MetricsInterceptor()
        gw = Gateway.builder().dev_mode(True).use(metrics).build()
        ...
        print(metrics.metrics.render())   # Prometheus exposition text

    Observation-only, so it always runs (including in dry-run).
    """

    def __init__(self, metrics: PrometheusMetrics | None = None) -> None:
        self.metrics = metrics or PrometheusMetrics()

    @property
    def name(self) -> str:
        return "metrics"

    @property
    def dry_run_safe(self) -> bool:
        return True

    async def after(self, response: GavioResponse, ctx: InterceptorContext) -> GavioResponse:
        self.metrics.record(
            response.provider,
            response.model,
            prompt_tokens=response.usage.prompt_tokens,
            completion_tokens=response.usage.completion_tokens,
            cost_usd=response.cost_usd,
            latency_ms=response.latency_ms,
            cache_hit=response.cache_hit,
        )
        return response
