# Interceptor-chain overhead benchmark

Answers one question with a number anyone can reproduce: **how much latency
does a realistic Gavio interceptor stack add to a provider call?**

It extends the methodology of [`../inspector/`](../inspector/README.md) —
a `MockProvider` padded with a fixed 5 ms delay, 20 warm-up and 200 measured
iterations, p50/p95 in microseconds, overhead as a percentage of the simulated
call — and runs it for the same three SDKs. To rerun it yourself, follow
[`REPRODUCE.md`](REPRODUCE.md). Results are published, machine-readable, in
[`../latest.json`](../latest.json) and, generated from it, as tables in
[`../RESULTS.md`](../RESULTS.md); this README deliberately holds no numbers.

## Configurations

Identical in the Python, JavaScript and Java harnesses, and defined once in
[`spec.json`](spec.json):

| Configuration | What runs | CI threshold |
|---|---|---|
| `baseline` | Raw provider call — the adapter is invoked directly, no gateway | — |
| `gateway` | Gateway with **no** interceptors (fixed cost of request/context/response plumbing) | < 10% |
| `audit` | Audit | < 20% |
| `audit_pii` | Audit + PII guard | < 50% |
| `audit_pii_ratelimit_guardrails` | Audit + PII guard + rate limiter + guardrails | < 60% |

Thresholds are percentages of the simulated 5 ms call and are **deliberately
loose**, like the Inspector ones: shared CI runners are noisy, so a breach is
meant to mean a structural regression (say, a scanner turning quadratic), not a
bad run. Typical local overhead is a small fraction of each threshold.

Overhead is always measured against `baseline`:
`overhead = p50(configuration) − p50(baseline)`, reported in µs and as a
percentage of the simulated provider call.

The stacks use the real interceptors with default settings, with these
benchmark-specific choices (all in `spec.json`):

- **Audit** writes to a sink that discards records (a counter), so disk or
  stdout speed doesn't leak into the result. The counter also proves the audit
  path ran for every call.
- **PII guard** has entity-type logging switched off. By default the JS and Java
  guards log detections to the console, which would measure terminal I/O rather
  than the chain.
- **Rate limiter** has ceilings far above the run (1M requests, 1B tokens per
  minute), so it does the full bookkeeping but never trips.
- **Guardrails** run a three-pattern regex denylist over every response; the
  mock response never matches, so it always passes.

## Methodology

- **Provider:** `MockProvider` plus a fixed 5 ms delay, so overhead is relative
  to a realistic (if fast) call rather than a microsecond-scale mock.
- **Iterations:** 20 warm-up (discarded) + 200 measured per configuration.
- **Interleaved:** each iteration times every configuration once, on the same
  payload, rotating which goes first. Clock drift, GC pauses and thermal
  throttling therefore hit every configuration alike instead of penalising
  whichever happened to run last. (The Inspector harnesses run configurations
  back-to-back; interleaving is the one change to that methodology.)
- **Percentiles:** nearest-rank, `ordered[ceil(q·n) − 1]`, identically in all
  three SDKs.
- **Self-checking:** every audited configuration must have written one record
  per call, and PII configurations must have detected entities, otherwise the
  run fails — a benchmark that silently measures nothing is worse than none.

### Payloads and seeds

Prompts come from a seeded generator that all three harnesses implement
identically, so one seed gives byte-identical prompts in every SDK. The recipe,
using the vocabulary and constants in `spec.json`:

1. PRNG: **mulberry32** (32-bit, `seed = 1337` by default).
2. For each of the 220 payloads, in this order of draws:
   1. word count = `48 + next() % 33`;
   2. that many words, each `vocabulary[next() % 64]`;
   3. `kind = next() % 4` — 0 and 1 are clean, 2 adds an email
      (`user{next() % 1000}@example.com`), 3 adds that email plus an IBAN and a
      phone number;
   4. each entity is inserted at position `next() % (words.length + 1)`.
3. Words are joined with single spaces and sent as one user message.

About half of the payloads therefore carry PII. The IBAN and phone are
synthetic. Each run reports `methodology.payloadDigest`, the SHA-256 of all
payloads joined by `\n`; `validate_latest.py` checks it is identical across
SDKs and equals `spec.json`'s `defaultPayloadDigest` for the default seed, so
any drift in the generator or vocabulary is caught.

## Output

Each harness prints one JSON run to stdout and, with `--out FILE`, writes it to
disk. `combine.py` merges the three runs into
[`../latest.json`](../latest.json). Every run carries the Gavio version, date,
SDK, seed, methodology, **runner and hardware** (OS, architecture, CPU model,
logical CPUs, memory, runtime, CI image when present — never hostname or user),
and per configuration `p50Us`, `p95Us`, `overheadP50Us`, `overheadPct`,
`budgetPct` and `pass`.

The contract is [`../latest.schema.json`](../latest.schema.json) (JSON Schema
2020-12). [`../validate_latest.py`](../validate_latest.py) checks it plus what a
schema cannot: overhead arithmetic, the configuration set against `spec.json`,
and cross-SDK parity of seed, payload digest and PII detections. It uses only
the standard library; if `jsonschema` is installed it also validates against the
schema file (`--require-schema` makes that mandatory, as CI does).

## Files

| File | Purpose |
|---|---|
| `spec.json` | Configurations, thresholds, seed, vocabulary, limits — the single source of truth for all three harnesses |
| `bench.py` / `bench.mjs` / `java/ChainOverheadBench.java` | The harnesses |
| `run.sh` | Builds prerequisites, runs one SDK or all, combines and validates |
| `combine.py` | Merges per-SDK runs into `../latest.json` |
| `REPRODUCE.md` | Step-by-step rerun instructions |

CI runs all of this in [`.github/workflows/benchmarks.yml`](../../.github/workflows/benchmarks.yml)
and uploads `latest.json` as an artifact.

## Reading the numbers

- Overhead differences below ~50 µs are within timer and scheduler noise on most
  machines; compare configurations by their *gaps*, not by single digits.
- Java runs with only 20 warm-up iterations (the shared methodology), so its
  figures include a largely cold JIT. They are a worst-case-ish view of a fresh
  process, not steady-state throughput.
- The simulated delay is a timer wake-up, and its jitter (hundreds of µs on a
  busy laptop, more in Java) is larger than the cost of the lighter stacks. A
  small or even slightly negative overhead for `gateway` or `audit` is noise, not
  a free lunch — rerun, or look at the heavier stacks where the signal is.
- This measures the in-process chain only. Network latency, TLS, provider time
  and token streaming are out of scope by construction — that is what the
  simulated delay stands in for.
- p95 on 200 samples is the 190th value; treat small p95 differences as noise.

## Changing the benchmark

Edit `spec.json`, not the harnesses, for thresholds, stacks, limits, seed or
vocabulary. Changing the vocabulary or generator changes `defaultPayloadDigest`
— update it deliberately. A new interceptor name needs a constructor in all
three harnesses (`build_interceptor` / `buildInterceptor`).
