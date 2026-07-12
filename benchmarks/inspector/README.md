# Inspector overhead benchmarks

Enforces the Inspector performance budget from `INSPECTOR_PLAN.md` §13
(`F-DX-09`): observation must never meaningfully slow a request.

## Methodology

Each harness measures per-request latency through a real gateway in three
configurations, identical across the three SDKs:

| Configuration | What runs |
|---|---|
| `disabled` (baseline) | No inspector — emission is a no-op branch |
| `metadata` | Bus + ring buffer + emitter, content-free events |
| `full` | Same, plus content capture and mutation-diff computation |

- The provider is a **MockProvider padded with a fixed 5 ms delay**, so
  overhead is measured against a realistic (if fast) provider call rather
  than a microsecond-scale mock.
- One **mutating interceptor** runs in every configuration, so `full` mode
  pays for its diff computation and the baseline pays the same chain cost.
- **No HTTP server** — the budget covers the request hot path (emit +
  assemble), not serving the UI.
- 20 warmup iterations, 200 measured; p50/p95 reported in µs; overhead =
  `p50(mode) − p50(disabled)`, expressed as a percentage of the simulated
  5 ms call.

## Budgets

`INSPECTOR_PLAN.md` §13 sets the steady-state budgets: disabled ≈ 0,
`metadata` < 1% p50, `full` < 5% (dev-only). The CI thresholds below are
deliberately looser to absorb shared-runner noise — a breach means something
structural regressed, not a noisy run:

| Mode | Steady-state budget | CI threshold (harness exit code) |
|---|---|---|
| `metadata` | < 1% p50 | **< 10%** of the simulated call (500 µs) |
| `full` | < 5% p50 | **< 25%** of the simulated call (1250 µs) |

Latest local release-prep check (2026-07-12): Python metadata 2.54% / full
5.05%, JavaScript metadata 0.51% / full 0.64%, Java metadata 1.27% / full
0.07%. All are below the CI thresholds above.

## Running

```bash
# Python (needs the SDK importable — pip install -e packages/gavio-py[dev] or run in-repo)
python3 benchmarks/inspector/bench.py

# JavaScript (needs the built SDK)
cd packages/gavio-js && npm ci && npm run build && cd ../..
node benchmarks/inspector/bench.mjs

# Java (excluded from the default surefire run; invoked explicitly)
cd packages/gavio-java && mvn -pl gavio-core test -Dtest=InspectorOverheadBench
```

Each harness prints a JSON summary and exits non-zero on a threshold breach.
CI runs all three in the `benchmarks` job on every PR.
