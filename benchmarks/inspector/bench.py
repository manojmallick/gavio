"""Inspector overhead benchmark — Python SDK (INSPECTOR_PLAN §13).

Measures per-request latency through the gateway in three configurations:
inspector disabled (baseline), metadata mode, and full mode — bus, ring
buffer, and emitter only; no HTTP server. The provider is a MockProvider
padded with a fixed simulated latency so relative overhead is meaningful,
and one mutating interceptor runs so full mode pays for its diff computation.

Prints a JSON summary and exits non-zero when the CI thresholds are breached:
metadata p50 overhead >= 10% of the simulated call, full >= 25%.

Run from the repo root:  python3 benchmarks/inspector/bench.py
"""

from __future__ import annotations

import asyncio
import json
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "packages" / "gavio-py"))

from gavio import Gateway  # noqa: E402
from gavio.context import InterceptorContext  # noqa: E402
from gavio.inspector import InspectorConfig  # noqa: E402
from gavio.interceptors.base import Interceptor  # noqa: E402
from gavio.providers.mock import MockProvider  # noqa: E402
from gavio.request import GavioRequest  # noqa: E402

SIMULATED_DELAY_MS = 5.0
WARMUP = 20
ITERATIONS = 200
METADATA_BUDGET_PCT = 10.0  # of the simulated provider call
FULL_BUDGET_PCT = 25.0

MESSAGES = [{"role": "user", "content": "benchmark the inspector overhead " * 8}]


class DelayedMockProvider(MockProvider):
    """MockProvider padded with a fixed delay to emulate a real provider call."""

    async def complete(self, request: GavioRequest):
        await asyncio.sleep(SIMULATED_DELAY_MS / 1000.0)
        return await super().complete(request)


class AnnotatorInterceptor(Interceptor):
    """Mutates every request so full mode computes a mutation diff."""

    @property
    def name(self) -> str:
        return "annotator"

    async def before(self, request: GavioRequest, ctx: InterceptorContext) -> GavioRequest:
        messages = [dict(m) for m in request.messages]
        messages[0]["content"] = messages[0]["content"] + " ·"
        return request.copy_with_messages(messages)


def build_gateway(mode: str | None) -> Gateway:
    builder = (
        Gateway.builder()
        .adapter(DelayedMockProvider())
        .model("mock")
        .use(AnnotatorInterceptor())
    )
    if mode is not None:
        builder.inspect(
            InspectorConfig(mode=mode, start_server=False, unsafe_content_capture_ack=True)
        )
    return builder.build()


async def measure(gateway: Gateway) -> list[float]:
    samples_us: list[float] = []
    for i in range(WARMUP + ITERATIONS):
        started = time.perf_counter_ns()
        await gateway.complete(messages=MESSAGES)
        elapsed_us = (time.perf_counter_ns() - started) / 1000.0
        if i >= WARMUP:
            samples_us.append(elapsed_us)
    return samples_us


def summarize(samples_us: list[float]) -> dict[str, float]:
    ordered = sorted(samples_us)
    return {
        "p50Us": round(statistics.median(ordered), 1),
        "p95Us": round(ordered[int(len(ordered) * 0.95) - 1], 1),
    }


async def main() -> int:
    results = {}
    for label, mode in (("disabled", None), ("metadata", "metadata"), ("full", "full")):
        results[label] = summarize(await measure(build_gateway(mode)))

    delay_us = SIMULATED_DELAY_MS * 1000.0
    baseline = results["disabled"]["p50Us"]
    for label, budget in (("metadata", METADATA_BUDGET_PCT), ("full", FULL_BUDGET_PCT)):
        overhead_us = round(results[label]["p50Us"] - baseline, 1)
        overhead_pct = round(overhead_us / delay_us * 100.0, 2)
        results[label]["overheadP50Us"] = overhead_us
        results[label]["overheadPct"] = overhead_pct
        results[label]["budgetPct"] = budget
        results[label]["pass"] = overhead_pct < budget

    ok = results["metadata"]["pass"] and results["full"]["pass"]
    print(
        json.dumps(
            {
                "benchmark": "inspector-overhead",
                "sdk": "python",
                "simulatedDelayMs": SIMULATED_DELAY_MS,
                "iterations": ITERATIONS,
                "results": results,
                "pass": ok,
            },
            indent=2,
        )
    )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
