"""Tests for Prometheus metrics (F-OBS-08)."""

from __future__ import annotations

from gavio import Gateway
from gavio.interceptors.metrics import MetricsInterceptor, PrometheusMetrics


def test_render_counters_and_histogram():
    m = PrometheusMetrics()
    m.record(
        "openai", "gpt-4o", prompt_tokens=10, completion_tokens=5, cost_usd=0.002, latency_ms=42
    )
    m.record(
        "openai", "gpt-4o", prompt_tokens=20, completion_tokens=8, cost_usd=0.004, latency_ms=8
    )
    text = m.render()

    assert 'gavio_requests_total{provider="openai",model="gpt-4o"} 2' in text
    assert 'gavio_tokens_total{provider="openai",model="gpt-4o",kind="prompt"} 30' in text
    assert 'gavio_tokens_total{provider="openai",model="gpt-4o",kind="completion"} 13' in text
    assert 'gavio_cost_usd_total{provider="openai",model="gpt-4o"} 0.006' in text
    # Histogram: le="10" has 1 (the 8ms obs), +Inf has 2, count 2, sum 50.
    assert 'gavio_request_latency_ms_bucket{provider="openai",model="gpt-4o",le="10"} 1' in text
    assert 'gavio_request_latency_ms_bucket{provider="openai",model="gpt-4o",le="+Inf"} 2' in text
    assert 'gavio_request_latency_ms_count{provider="openai",model="gpt-4o"} 2' in text
    assert 'gavio_request_latency_ms_sum{provider="openai",model="gpt-4o"} 50' in text


def test_type_and_help_lines_present():
    text = PrometheusMetrics().render()
    for metric in (
        "gavio_requests_total",
        "gavio_tokens_total",
        "gavio_cost_usd_total",
        "gavio_request_latency_ms",
        "gavio_cache_hits_total",
    ):
        assert f"# HELP {metric}" in text
        assert f"# TYPE {metric}" in text
    assert text.endswith("\n")


def test_cache_hits_counted():
    m = PrometheusMetrics()
    m.record("mock", "mock", cache_hit=True)
    m.record("mock", "mock", cache_hit=False)
    assert 'gavio_cache_hits_total{provider="mock",model="mock"} 1' in m.render()


def test_labels_separate_by_provider_and_model():
    m = PrometheusMetrics()
    m.record("openai", "gpt-4o")
    m.record("anthropic", "claude-sonnet-4-6")
    text = m.render()
    assert 'gavio_requests_total{provider="openai",model="gpt-4o"} 1' in text
    assert 'gavio_requests_total{provider="anthropic",model="claude-sonnet-4-6"} 1' in text


async def test_interceptor_records_from_gateway():
    metrics = MetricsInterceptor()
    gw = Gateway.builder().dev_mode(True).use(metrics).build()
    for i in range(3):
        await gw.complete(messages=[{"role": "user", "content": f"msg {i}"}])

    text = metrics.metrics.render()
    assert 'gavio_requests_total{provider="mock",model="mock"} 3' in text
    assert 'gavio_request_latency_ms_count{provider="mock",model="mock"} 3' in text
