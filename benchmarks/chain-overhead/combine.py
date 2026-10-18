"""Combine per-SDK chain-overhead runs into benchmarks/latest.json (F-TRUST-02).

Each harness (bench.py, bench.mjs, java/ChainOverheadBench.java) emits one run
as JSON. This merges them — python, js, java order — into a single file:

    {schemaVersion, benchmark, gavioVersion, generatedAt, pass, runs: [...]}

and refuses to merge runs that disagree on Gavio version, seed, or payload
digest, since those would not be comparable. Validate the result with
benchmarks/validate_latest.py.

    python3 benchmarks/chain-overhead/combine.py               # out/*.json -> latest.json
    python3 benchmarks/chain-overhead/combine.py a.json b.json --out merged.json
    python3 benchmarks/chain-overhead/combine.py --markdown    # also print a Markdown table
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SDK_ORDER = ["python", "js", "java"]
SCHEMA_VERSION = 1

# The Java harness's JSON writer prints whole-number doubles as integers; make
# the combined file uniform regardless of which SDK produced a field.
_FLOAT_METHODOLOGY = ("simulatedDelayMs",)
_FLOAT_RUNNER = ("memoryGb",)
_FLOAT_CONFIG = ("p50Us", "p95Us", "overheadP50Us", "overheadPct", "budgetPct")


def _floatify(obj: dict, keys: tuple[str, ...]) -> None:
    for key in keys:
        if isinstance(obj.get(key), (int, float)) and not isinstance(obj.get(key), bool):
            obj[key] = float(obj[key])


def normalize(run: dict) -> dict:
    _floatify(run["methodology"], _FLOAT_METHODOLOGY)
    _floatify(run["runner"], _FLOAT_RUNNER)
    for config in run["configurations"]:
        _floatify(config, _FLOAT_CONFIG)
    return run


def combine(runs: list[dict]) -> dict:
    if not runs:
        raise SystemExit("combine: no runs given")
    sdks = [run["sdk"] for run in runs]
    if len(set(sdks)) != len(sdks):
        raise SystemExit(f"combine: duplicate SDK in runs: {sdks}")
    for field, getter in (
        ("gavioVersion", lambda r: r["gavioVersion"]),
        ("seed", lambda r: r["seed"]),
        ("payloadDigest", lambda r: r["methodology"]["payloadDigest"]),
    ):
        values = {getter(run) for run in runs}
        if len(values) != 1:
            raise SystemExit(f"combine: runs disagree on {field}: {sorted(map(str, values))}")
    ordered = sorted(runs, key=lambda r: SDK_ORDER.index(r["sdk"]) if r["sdk"] in SDK_ORDER else 99)
    return {
        "schemaVersion": SCHEMA_VERSION,
        "benchmark": "chain-overhead",
        "gavioVersion": ordered[0]["gavioVersion"],
        "generatedAt": max(run["date"] for run in ordered),
        "pass": all(run["pass"] for run in ordered),
        "runs": [normalize(run) for run in ordered],
    }


def markdown(combined: dict) -> str:
    lines = [
        f"### Chain overhead — Gavio {combined['gavioVersion']}",
        "",
        "| SDK | Configuration | p50 µs | p95 µs | Overhead µs | Overhead % | Budget % | Pass |",
        "|---|---|---:|---:|---:|---:|---:|:---:|",
    ]
    for run in combined["runs"]:
        for c in run["configurations"]:
            budget = "—" if c["budgetPct"] is None else f"{c['budgetPct']:g}"
            lines.append(
                f"| {run['sdk']} | `{c['configuration']}` | {c['p50Us']:.1f} | {c['p95Us']:.1f} "
                f"| {c['overheadP50Us']:.1f} | {c['overheadPct']:.2f} | {budget} "
                f"| {'✅' if c['pass'] else '❌'} |"
            )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "runs", nargs="*", type=Path, help="per-SDK run files (default: out/*.json)"
    )
    parser.add_argument("--out", type=Path, default=HERE.parent / "latest.json")
    parser.add_argument("--markdown", action="store_true", help="print a Markdown summary table")
    args = parser.parse_args()

    paths = args.runs or [HERE / "out" / f"{sdk}.json" for sdk in SDK_ORDER]
    runs = []
    for path in paths:
        if not path.is_file():
            if args.runs:
                raise SystemExit(f"combine: {path} not found")
            continue  # default mode: combine whatever has been run
        runs.append(json.loads(path.read_text(encoding="utf-8")))

    combined = combine(runs)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(combined, indent=2) + "\n", encoding="utf-8")
    print(
        f"wrote {args.out} ({', '.join(run['sdk'] for run in combined['runs'])})", file=sys.stderr
    )
    if args.markdown:
        print(markdown(combined))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
