"""Prometheus metrics registry (F-OBS-08) — zero-dependency exposition.

Holds counters and a latency histogram keyed by ``(provider, model)`` and renders
them in the Prometheus text exposition format. No ``prometheus_client`` — the
format is hand-rolled so the core stays dependency-free.
"""

from __future__ import annotations

import threading

# Cumulative histogram bucket upper bounds, in milliseconds.
_LATENCY_BUCKETS: tuple[float, ...] = (5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000)


def _num(value: float) -> str:
    """Format a number the Prometheus way — integers without a trailing ``.0``."""
    f = float(value)
    if f.is_integer():
        return str(int(f))
    return repr(f)


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")


def _labels(pairs: list[tuple[str, str]]) -> str:
    inner = ",".join(f'{k}="{_escape(str(v))}"' for k, v in pairs)
    return "{" + inner + "}"


class _Histogram:
    __slots__ = ("bucket_counts", "sum", "count")

    def __init__(self) -> None:
        # bucket_counts[i] is the cumulative count of observations <= buckets[i].
        self.bucket_counts = [0] * len(_LATENCY_BUCKETS)
        self.sum = 0.0
        self.count = 0

    def observe(self, value: float) -> None:
        self.count += 1
        self.sum += value
        for i, upper in enumerate(_LATENCY_BUCKETS):
            if value <= upper:
                self.bucket_counts[i] += 1


class PrometheusMetrics:
    """Thread-safe in-memory metrics, rendered as Prometheus exposition text.

    All series are labelled by ``provider`` and ``model``. Feed it with
    :meth:`record` (the :class:`MetricsInterceptor` does this per request) and
    scrape :meth:`render`.
    """

    def __init__(self, namespace: str = "gavio") -> None:
        self._ns = namespace
        self._lock = threading.Lock()
        self._requests: dict[tuple[str, str], int] = {}
        self._tokens: dict[tuple[str, str, str], int] = {}
        self._cost: dict[tuple[str, str], float] = {}
        self._cache_hits: dict[tuple[str, str], int] = {}
        self._latency: dict[tuple[str, str], _Histogram] = {}

    def record(
        self,
        provider: str,
        model: str,
        *,
        prompt_tokens: int = 0,
        completion_tokens: int = 0,
        cost_usd: float = 0.0,
        latency_ms: float = 0,
        cache_hit: bool = False,
    ) -> None:
        key = (provider, model)
        with self._lock:
            self._requests[key] = self._requests.get(key, 0) + 1
            pk = (provider, model, "prompt")
            ck = (provider, model, "completion")
            self._tokens[pk] = self._tokens.get(pk, 0) + prompt_tokens
            self._tokens[ck] = self._tokens.get(ck, 0) + completion_tokens
            self._cost[key] = self._cost.get(key, 0.0) + cost_usd
            hist = self._latency.get(key)
            if hist is None:
                hist = self._latency[key] = _Histogram()
            hist.observe(latency_ms)
            if cache_hit:
                self._cache_hits[key] = self._cache_hits.get(key, 0) + 1

    def render(self) -> str:
        """Return the Prometheus text exposition of all metrics."""
        ns = self._ns
        out: list[str] = []
        with self._lock:
            out.append(f"# HELP {ns}_requests_total Total gateway requests.")
            out.append(f"# TYPE {ns}_requests_total counter")
            for (provider, model), v in sorted(self._requests.items()):
                labels = _labels([("provider", provider), ("model", model)])
                out.append(f"{ns}_requests_total{labels} {_num(v)}")

            out.append(f"# HELP {ns}_tokens_total Total tokens processed.")
            out.append(f"# TYPE {ns}_tokens_total counter")
            for (provider, model, kind), v in sorted(self._tokens.items()):
                labels = _labels([("provider", provider), ("model", model), ("kind", kind)])
                out.append(f"{ns}_tokens_total{labels} {_num(v)}")

            out.append(f"# HELP {ns}_cost_usd_total Total estimated cost in USD.")
            out.append(f"# TYPE {ns}_cost_usd_total counter")
            for (provider, model), v in sorted(self._cost.items()):
                labels = _labels([("provider", provider), ("model", model)])
                out.append(f"{ns}_cost_usd_total{labels} {_num(v)}")

            out.append(f"# HELP {ns}_request_latency_ms Request latency in milliseconds.")
            out.append(f"# TYPE {ns}_request_latency_ms histogram")
            for (provider, model), hist in sorted(self._latency.items()):
                for i, upper in enumerate(_LATENCY_BUCKETS):
                    labels = _labels(
                        [("provider", provider), ("model", model), ("le", _num(upper))]
                    )
                    out.append(f"{ns}_request_latency_ms_bucket{labels} {hist.bucket_counts[i]}")
                inf = _labels([("provider", provider), ("model", model), ("le", "+Inf")])
                base = _labels([("provider", provider), ("model", model)])
                out.append(f"{ns}_request_latency_ms_bucket{inf} {hist.count}")
                out.append(f"{ns}_request_latency_ms_sum{base} {_num(hist.sum)}")
                out.append(f"{ns}_request_latency_ms_count{base} {hist.count}")

            out.append(f"# HELP {ns}_cache_hits_total Total cache hits.")
            out.append(f"# TYPE {ns}_cache_hits_total counter")
            for (provider, model), v in sorted(self._cache_hits.items()):
                labels = _labels([("provider", provider), ("model", model)])
                out.append(f"{ns}_cache_hits_total{labels} {_num(v)}")

        return "\n".join(out) + "\n"
