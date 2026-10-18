"""Interceptor-chain overhead benchmark — Python SDK (F-TRUST-02).

Measures what a realistic default interceptor stack costs on top of a raw
provider call. The provider is a MockProvider padded with a fixed 5 ms delay
(same methodology as benchmarks/inspector/), so overhead is expressed against a
realistic — if fast — call rather than a microsecond-scale mock.

The configurations, thresholds, seed and payload recipe live in spec.json and
are shared by the Python, JavaScript and Java harnesses. Payloads come from a
seeded generator that all three implement identically (see README.md), so the
same seed yields byte-identical prompts in every SDK; a SHA-256 digest of them
is reported and cross-checked.

Prints the run as JSON on stdout (and to --out). Exits non-zero when a CI
threshold is breached: those are deliberately loose, so a breach means a
structural regression rather than noise.

Run from the repo root:  python3 benchmarks/chain-overhead/bench.py
"""

from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import math
import os
import platform
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "packages" / "gavio-py"))

import gavio  # noqa: E402
from gavio import Gateway  # noqa: E402
from gavio.interceptors.audit import AuditInterceptor, AuditRecord, AuditSink  # noqa: E402
from gavio.interceptors.governance import RateLimiter  # noqa: E402
from gavio.interceptors.guardrails import (  # noqa: E402
    GuardrailsInterceptor,
    RegexDenylistValidator,
)
from gavio.interceptors.pii import PiiGuard  # noqa: E402
from gavio.providers.mock import MockProvider  # noqa: E402
from gavio.request import GavioRequest  # noqa: E402
from gavio.types import Provider  # noqa: E402

SCHEMA_VERSION = 1
SDK = "python"
SPEC = json.loads((HERE / "spec.json").read_text(encoding="utf-8"))
SIMULATED_DELAY_MS = SPEC["simulatedDelayMs"]


# ── Seeded payload generator (recipe shared by all three harnesses) ─────────


class Mulberry32:
    """mulberry32 — a 32-bit PRNG that is trivial to port exactly."""

    def __init__(self, seed: int) -> None:
        self._state = seed & 0xFFFFFFFF

    def next_u32(self) -> int:
        self._state = (self._state + 0x6D2B79F5) & 0xFFFFFFFF
        t = self._state
        t = ((t ^ (t >> 15)) * (1 | t)) & 0xFFFFFFFF
        t = ((t + (((t ^ (t >> 7)) * (61 | t)) & 0xFFFFFFFF)) & 0xFFFFFFFF) ^ t
        return (t ^ (t >> 14)) & 0xFFFFFFFF


def build_payloads(seed: int, count: int) -> list[str]:
    spec = SPEC["payload"]
    vocab = spec["vocabulary"]
    rng = Mulberry32(seed)
    payloads = []
    for _ in range(count):
        n_words = spec["wordCountMin"] + rng.next_u32() % spec["wordCountSpan"]
        words = [vocab[rng.next_u32() % len(vocab)] for _ in range(n_words)]
        kind = rng.next_u32() % 4  # 0,1 clean · 2 email · 3 email + IBAN + phone
        entities: list[str] = []
        if kind >= 2:
            n = rng.next_u32() % spec["emailModulus"]
            entities.append(f"{spec['emailPrefix']}{n}@{spec['emailDomain']}")
        if kind == 3:
            entities.extend([spec["iban"], spec["phone"]])
        for entity in entities:
            words.insert(rng.next_u32() % (len(words) + 1), entity)
        payloads.append(" ".join(words))
    return payloads


def payload_digest(payloads: list[str]) -> str:
    return "sha256:" + hashlib.sha256("\n".join(payloads).encode("utf-8")).hexdigest()


# ── Provider, sink, and per-configuration subjects ──────────────────────────


class DelayedMockProvider(MockProvider):
    """MockProvider padded with a fixed delay to emulate a real provider call."""

    async def complete(self, request: GavioRequest):
        await asyncio.sleep(SIMULATED_DELAY_MS / 1000.0)
        return await super().complete(request)


class CountingSink(AuditSink):
    """Discards records (no I/O noise) but proves the audit path really ran."""

    def __init__(self) -> None:
        self.records = 0
        self.pii_entities = 0

    async def write(self, record: AuditRecord) -> None:
        self.records += 1
        self.pii_entities += sum(record.pii_entity_counts.values())


class Subject:
    """One benchmarked configuration: an async callable plus its audit sink."""

    def __init__(self, call, sink: CountingSink | None = None) -> None:
        self.call = call
        self.sink = sink


def build_interceptor(name: str, sink: CountingSink):
    if name == "audit":
        return AuditInterceptor(sink)
    if name == "pii_guard":
        return PiiGuard(log_entity_types=SPEC["piiGuard"]["logEntityTypes"])
    if name == "rate_limiter":
        limits = SPEC["rateLimiter"]
        return RateLimiter(
            max_requests_per_minute=limits["maxRequestsPerMinute"],
            max_tokens_per_minute=limits["maxTokensPerMinute"],
        )
    if name == "guardrails":
        return GuardrailsInterceptor([RegexDenylistValidator(SPEC["guardrailsDenylist"])])
    raise ValueError(f"unknown interceptor in spec.json: {name}")


def build_subject(config: dict) -> Subject:
    adapter = DelayedMockProvider()
    if not config["gateway"]:

        async def raw(messages):
            request = GavioRequest(messages=messages, model="mock", provider=Provider.MOCK)
            return await adapter.complete(request)

        return Subject(raw)

    sink = CountingSink()
    builder = Gateway.builder().adapter(adapter).model("mock")
    for name in config["interceptors"]:
        builder.use(build_interceptor(name, sink))
    gateway = builder.build()

    async def through_gateway(messages):
        return await gateway.complete(messages=messages)

    return Subject(through_gateway, sink if "audit" in config["interceptors"] else None)


# ── Measurement ─────────────────────────────────────────────────────────────


async def measure(
    subjects: dict[str, Subject], payloads: list[str], warmup: int
) -> dict[str, list[float]]:
    """Interleaved round-robin: every iteration times each configuration once.

    The same payload is used for all configurations in an iteration and the
    starting configuration rotates, so clock drift, GC and thermal effects hit
    every configuration equally instead of biasing whichever ran last.
    """
    names = list(subjects)
    samples: dict[str, list[float]] = {name: [] for name in names}
    for i, text in enumerate(payloads):
        messages = [{"role": "user", "content": text}]
        for j in range(len(names)):
            name = names[(i + j) % len(names)]
            started = time.perf_counter_ns()
            await subjects[name].call(messages)
            elapsed_us = (time.perf_counter_ns() - started) / 1000.0
            if i >= warmup:
                samples[name].append(elapsed_us)
    return samples


def percentile(samples: list[float], q: float) -> float:
    """Nearest-rank percentile: ordered[ceil(q * n) - 1]."""
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(q * len(ordered)) - 1)]


# ── Runner / hardware info ──────────────────────────────────────────────────


def _sysctl(key: str) -> str | None:
    try:
        out = subprocess.run(
            ["sysctl", "-n", key], capture_output=True, text=True, timeout=5, check=True
        )
        return out.stdout.strip() or None
    except (OSError, subprocess.SubprocessError):
        return None


def _cpu_model() -> str:
    system = platform.system()
    if system == "Darwin":
        return _sysctl("machdep.cpu.brand_string") or "unknown"
    if system == "Linux":
        try:
            for line in Path("/proc/cpuinfo").read_text().splitlines():
                if line.lower().startswith(("model name", "hardware")):
                    return line.split(":", 1)[1].strip()
        except OSError:
            pass
    return platform.processor() or "unknown"


def _memory_gb() -> float | None:
    system = platform.system()
    try:
        if system == "Darwin":
            raw = _sysctl("hw.memsize")
            return round(int(raw) / 1024**3, 1) if raw else None
        if system == "Linux":
            for line in Path("/proc/meminfo").read_text().splitlines():
                if line.startswith("MemTotal:"):
                    return round(int(line.split()[1]) / 1024**2, 1)
    except (OSError, ValueError):
        pass
    return None


def runner_info() -> dict:
    families = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}
    arches = {"aarch64": "arm64", "arm64": "arm64", "amd64": "x86_64", "x86_64": "x86_64"}
    in_ci = os.environ.get("GITHUB_ACTIONS") == "true"
    image = "/".join(filter(None, (os.environ.get("ImageOS"), os.environ.get("ImageVersion"))))
    return {
        "os": families.get(platform.system(), platform.system().lower()),
        "osRelease": platform.release(),
        "arch": arches.get(platform.machine().lower(), platform.machine().lower()),
        "cpuModel": _cpu_model(),
        "cpuCount": os.cpu_count(),
        "memoryGb": _memory_gb(),
        "runtime": {"name": platform.python_implementation(), "version": platform.python_version()},
        "ci": in_ci,
        "ciProvider": "github-actions" if in_ci else None,
        "ciImage": (image or None) if in_ci else None,
    }


# ── Entry point ─────────────────────────────────────────────────────────────


async def run(seed: int, warmup: int, iterations: int) -> dict:
    payloads = build_payloads(seed, warmup + iterations)
    subjects = {c["id"]: build_subject(c) for c in SPEC["configurations"]}
    samples = await measure(subjects, payloads, warmup)

    delay_us = SIMULATED_DELAY_MS * 1000.0
    baseline_p50 = round(percentile(samples["baseline"], 0.5), 1)
    configurations = []
    ok = True
    for config in SPEC["configurations"]:
        name, budget = config["id"], config["budgetPct"]
        p50 = round(percentile(samples[name], 0.5), 1)
        p95 = round(percentile(samples[name], 0.95), 1)
        overhead_us = round(p50 - baseline_p50, 1)
        overhead_pct = round(overhead_us / delay_us * 100.0, 2)
        passed = budget is None or overhead_pct < budget

        # Prove the stack really ran: every call audited, PII really detected.
        sink = subjects[name].sink
        checks: dict[str, int] = {}
        if sink is not None:
            checks["auditRecords"] = sink.records
            checks["piiEntities"] = sink.pii_entities
            passed = passed and sink.records == len(payloads)
            if "pii_guard" in config["interceptors"]:
                passed = passed and sink.pii_entities > 0

        ok = ok and passed
        configurations.append(
            {
                "configuration": name,
                "description": config["description"],
                "interceptors": config["interceptors"],
                "p50Us": p50,
                "p95Us": p95,
                "overheadP50Us": overhead_us,
                "overheadPct": overhead_pct,
                "budgetPct": budget,
                "checks": checks,
                "pass": passed,
            }
        )

    return {
        "schemaVersion": SCHEMA_VERSION,
        "benchmark": "chain-overhead",
        "sdk": SDK,
        "gavioVersion": gavio.__version__,
        "date": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "seed": seed,
        "methodology": {
            "simulatedDelayMs": SIMULATED_DELAY_MS,
            "warmup": warmup,
            "iterations": iterations,
            "percentile": "nearest-rank",
            "interleaved": True,
            "payloadCount": len(payloads),
            "payloadDigest": payload_digest(payloads),
        },
        "runner": runner_info(),
        "configurations": configurations,
        "pass": ok,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--seed", type=int, default=SPEC["seed"])
    parser.add_argument("--warmup", type=int, default=SPEC["warmup"])
    parser.add_argument("--iterations", type=int, default=SPEC["iterations"])
    parser.add_argument("--out", type=Path, help="also write the JSON run to this file")
    args = parser.parse_args()

    result = asyncio.run(run(args.seed, args.warmup, args.iterations))
    text = json.dumps(result, indent=2)
    print(text)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text + "\n", encoding="utf-8")
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
