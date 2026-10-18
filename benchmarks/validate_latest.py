"""Validate benchmarks/latest.json (F-TRUST-02).

Standard library only. Two layers:

1. Structure and semantics, always run: required fields and types, the
   configuration set against benchmarks/chain-overhead/spec.json, overhead
   arithmetic (overhead == p50 - baseline p50, percent of the simulated call),
   and cross-SDK parity (same seed, same payload digest, same PII detections).
2. benchmarks/latest.schema.json, run when the optional ``jsonschema`` package
   is installed (``--require-schema`` makes its absence an error).

    python3 benchmarks/validate_latest.py                    # benchmarks/latest.json
    python3 benchmarks/validate_latest.py some/latest.json
    python3 benchmarks/validate_latest.py --run out/python.json   # one harness run
    python3 benchmarks/validate_latest.py --allow-partial         # fewer than 3 SDKs

Exits 0 when valid, 1 with a list of problems otherwise.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCHEMA_PATH = HERE / "latest.schema.json"
SPEC = json.loads((HERE / "chain-overhead" / "spec.json").read_text(encoding="utf-8"))
SDKS = ("python", "js", "java")
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
VERSION = re.compile(r"^\d+\.\d+\.\d+([-+][0-9A-Za-z.-]+)?$")

RUN_KEYS = {
    "schemaVersion",
    "benchmark",
    "sdk",
    "gavioVersion",
    "date",
    "seed",
    "methodology",
    "runner",
    "configurations",
    "pass",
}
METHODOLOGY_KEYS = {
    "simulatedDelayMs",
    "warmup",
    "iterations",
    "percentile",
    "interleaved",
    "payloadCount",
    "payloadDigest",
}
RUNNER_KEYS = {
    "os",
    "osRelease",
    "arch",
    "cpuModel",
    "cpuCount",
    "memoryGb",
    "runtime",
    "ci",
    "ciProvider",
    "ciImage",
}
CONFIG_KEYS = {
    "configuration",
    "description",
    "interceptors",
    "p50Us",
    "p95Us",
    "overheadP50Us",
    "overheadPct",
    "budgetPct",
    "checks",
    "pass",
}


def _is_number(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _is_int(value: object) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


class Problems(list):
    def add(self, where: str, message: str) -> None:
        self.append(f"{where}: {message}")


def _exact_keys(problems: Problems, where: str, obj: object, keys: set[str]) -> bool:
    if not isinstance(obj, dict):
        problems.add(where, "must be an object")
        return False
    for key in sorted(keys - obj.keys()):
        problems.add(where, f"missing field '{key}'")
    for key in sorted(obj.keys() - keys):
        problems.add(where, f"unexpected field '{key}'")
    return keys <= obj.keys()


def check_configuration(
    problems: Problems, where: str, config: dict, run: dict, baseline: float
) -> None:
    before = len(problems)
    if not _exact_keys(problems, where, config, CONFIG_KEYS):
        return
    for key in ("p50Us", "p95Us"):
        if not _is_number(config[key]) or config[key] <= 0:
            problems.add(where, f"{key} must be a positive number")
    for key in ("overheadP50Us", "overheadPct"):
        if not _is_number(config[key]):
            problems.add(where, f"{key} must be a number")
    if not (config["budgetPct"] is None or _is_number(config["budgetPct"])):
        problems.add(where, "budgetPct must be a number or null")
    if not isinstance(config["pass"], bool):
        problems.add(where, "pass must be a boolean")
    if not isinstance(config["checks"], dict) or not set(config["checks"]) <= {
        "auditRecords",
        "piiEntities",
    }:
        problems.add(where, "checks may only contain auditRecords and piiEntities")
    if len(problems) > before:
        return

    if config["p95Us"] < config["p50Us"]:
        problems.add(where, f"p95Us {config['p95Us']} is below p50Us {config['p50Us']}")

    delay_us = run["methodology"]["simulatedDelayMs"] * 1000.0
    expected_us = config["p50Us"] - baseline
    if abs(config["overheadP50Us"] - expected_us) > 0.15:
        problems.add(
            where,
            f"overheadP50Us {config['overheadP50Us']} != p50 - baseline p50 ({expected_us:.1f})",
        )
    if abs(config["overheadPct"] - config["overheadP50Us"] / delay_us * 100.0) > 0.02:
        problems.add(where, f"overheadPct {config['overheadPct']} inconsistent with overheadP50Us")

    # `pass` must be exactly: within budget AND the stack provably ran.
    checks, checks_ok = config["checks"], True
    if "audit" in config["interceptors"]:
        if checks.get("auditRecords") != run["methodology"]["payloadCount"]:
            problems.add(
                where, "auditRecords != payloadCount — the audit path did not run for every call"
            )
            checks_ok = False
        if "pii_guard" in config["interceptors"] and not checks.get("piiEntities"):
            problems.add(where, "pii_guard configured but no PII entities were detected")
            checks_ok = False
    elif checks:
        problems.add(where, "checks present without the audit interceptor")
    within_budget = config["budgetPct"] is None or config["overheadPct"] < config["budgetPct"]
    if config["pass"] != (within_budget and checks_ok):
        problems.add(
            where,
            f"pass={config['pass']} but overheadPct={config['overheadPct']} "
            f"budgetPct={config['budgetPct']} checksOk={checks_ok}",
        )


def check_run(problems: Problems, where: str, run: dict) -> None:
    before = len(problems)
    if not _exact_keys(problems, where, run, RUN_KEYS):
        return
    if run["schemaVersion"] != 1:
        problems.add(where, "schemaVersion must be 1")
    if run["benchmark"] != "chain-overhead":
        problems.add(where, "benchmark must be 'chain-overhead'")
    if run["sdk"] not in SDKS:
        problems.add(where, f"sdk must be one of {SDKS}")
    if not (isinstance(run["gavioVersion"], str) and VERSION.match(run["gavioVersion"])):
        problems.add(where, "gavioVersion must look like 3.1.0")
    if not (isinstance(run["date"], str) and TIMESTAMP.match(run["date"])):
        problems.add(where, "date must be an ISO-8601 UTC timestamp (YYYY-MM-DDTHH:MM:SSZ)")
    if not _is_int(run["seed"]):
        problems.add(where, "seed must be an integer")
    if not isinstance(run["pass"], bool):
        problems.add(where, "pass must be a boolean")

    meth = run["methodology"]
    if _exact_keys(problems, f"{where}.methodology", meth, METHODOLOGY_KEYS):
        if not (_is_number(meth["simulatedDelayMs"]) and meth["simulatedDelayMs"] > 0):
            problems.add(f"{where}.methodology", "simulatedDelayMs must be a positive number")
        if meth["percentile"] != "nearest-rank":
            problems.add(f"{where}.methodology", "percentile must be 'nearest-rank'")
        if not (isinstance(meth["payloadDigest"], str) and DIGEST.match(meth["payloadDigest"])):
            problems.add(f"{where}.methodology", "payloadDigest must be sha256:<64 hex>")
        for key in ("warmup", "iterations", "payloadCount"):
            if not _is_int(meth[key]) or meth[key] < 0:
                problems.add(f"{where}.methodology", f"{key} must be a non-negative integer")
        if len(problems) == before and meth["payloadCount"] != meth["warmup"] + meth["iterations"]:
            problems.add(f"{where}.methodology", "payloadCount != warmup + iterations")

    runner = run["runner"]
    if _exact_keys(problems, f"{where}.runner", runner, RUNNER_KEYS):
        for key in ("os", "osRelease", "arch", "cpuModel"):
            if not (isinstance(runner[key], str) and runner[key]):
                problems.add(f"{where}.runner", f"{key} must be a non-empty string")
        if not (_is_int(runner["cpuCount"]) and runner["cpuCount"] >= 1):
            problems.add(f"{where}.runner", "cpuCount must be a positive integer")
        if not (
            runner["memoryGb"] is None
            or (_is_number(runner["memoryGb"]) and runner["memoryGb"] > 0)
        ):
            problems.add(f"{where}.runner", "memoryGb must be a positive number or null")
        runtime = runner["runtime"]
        if not (
            isinstance(runtime, dict)
            and set(runtime) == {"name", "version"}
            and all(isinstance(v, str) and v for v in runtime.values())
        ):
            problems.add(f"{where}.runner", "runtime must be {name, version} strings")

    configs = run["configurations"]
    if not isinstance(configs, list):
        problems.add(where, "configurations must be an array")
        return
    expected = SPEC["configurations"]
    if [c.get("configuration") for c in configs] != [c["id"] for c in expected]:
        problems.add(
            where,
            "configurations differ from spec.json: "
            f"{[c.get('configuration') for c in configs]} vs {[c['id'] for c in expected]}",
        )
        return
    if len(problems) > before or not isinstance(configs[0], dict):
        return
    baseline = configs[0].get("p50Us")
    for config, spec_config in zip(configs, expected, strict=True):
        cwhere = f"{where}.{config.get('configuration')}"
        if config.get("interceptors") != spec_config["interceptors"]:
            problems.add(
                cwhere, f"interceptors differ from spec.json ({spec_config['interceptors']})"
            )
        if config.get("budgetPct") != spec_config["budgetPct"]:
            problems.add(cwhere, f"budgetPct differs from spec.json ({spec_config['budgetPct']})")
        check_configuration(problems, cwhere, config, run, baseline)
    if configs[0].get("overheadP50Us") != 0:
        problems.add(f"{where}.baseline", "baseline overheadP50Us must be 0")
    if len(problems) == before and run["pass"] != all(c["pass"] for c in configs):
        problems.add(where, "run 'pass' disagrees with its configurations")


def check_combined(problems: Problems, data: dict, allow_partial: bool) -> None:
    keys = {"schemaVersion", "benchmark", "gavioVersion", "generatedAt", "pass", "runs"}
    if not _exact_keys(problems, "latest", data, keys):
        return
    if data["schemaVersion"] != 1:
        problems.add("latest", "schemaVersion must be 1")
    if data["benchmark"] != "chain-overhead":
        problems.add("latest", "benchmark must be 'chain-overhead'")
    if not (isinstance(data["generatedAt"], str) and TIMESTAMP.match(data["generatedAt"])):
        problems.add("latest", "generatedAt must be an ISO-8601 UTC timestamp")
    runs = data["runs"]
    if not isinstance(runs, list) or not runs:
        problems.add("latest", "runs must be a non-empty array")
        return
    before = len(problems)
    for index, run in enumerate(runs):
        check_run(
            problems, f"runs[{index}]({run.get('sdk') if isinstance(run, dict) else '?'})", run
        )
    if len(problems) > before:
        return

    sdks = [run["sdk"] for run in runs]
    if len(set(sdks)) != len(sdks):
        problems.add("latest", f"duplicate SDKs: {sdks}")
    if not allow_partial and set(sdks) != set(SDKS):
        problems.add(
            "latest", f"expected runs for {sorted(SDKS)}, got {sorted(sdks)} (use --allow-partial)"
        )
    for field, getter in (
        ("gavioVersion", lambda r: r["gavioVersion"]),
        ("seed", lambda r: r["seed"]),
        ("methodology.payloadDigest", lambda r: r["methodology"]["payloadDigest"]),
        ("methodology.warmup", lambda r: r["methodology"]["warmup"]),
        ("methodology.iterations", lambda r: r["methodology"]["iterations"]),
        ("methodology.simulatedDelayMs", lambda r: r["methodology"]["simulatedDelayMs"]),
    ):
        if len({getter(run) for run in runs}) != 1:
            problems.add("latest", f"runs disagree on {field}")
    # The generator is pinned: the default seed must keep producing the same prompts.
    for run in runs:
        meth = run["methodology"]
        if (
            run["seed"] == SPEC["seed"]
            and meth["payloadCount"] == SPEC["warmup"] + SPEC["iterations"]
            and meth["payloadDigest"] != SPEC["defaultPayloadDigest"]
        ):
            problems.add(
                f"latest.{run['sdk']}",
                "payloadDigest differs from spec.json defaultPayloadDigest — "
                "the payload generator or vocabulary changed",
            )
    if data["gavioVersion"] != runs[0]["gavioVersion"]:
        problems.add("latest", "top-level gavioVersion differs from the runs")
    if data["generatedAt"] != max(run["date"] for run in runs):
        problems.add("latest", "generatedAt must equal the newest run date")
    if data["pass"] != all(run["pass"] for run in runs):
        problems.add("latest", "top-level 'pass' disagrees with the runs")

    # Same payloads must trip the same detectors in every SDK.
    detections = {
        run["sdk"]: [c["checks"].get("piiEntities") for c in run["configurations"]] for run in runs
    }
    if len({tuple(v) for v in detections.values()}) > 1:
        problems.add("latest", f"PII detections differ across SDKs: {detections}")


def schema_check(problems: Problems, data: dict, as_run: bool, require: bool) -> str:
    try:
        import jsonschema
    except ImportError:
        if require:
            problems.add("schema", "jsonschema is not installed (pip install jsonschema)")
        return "skipped (jsonschema not installed)"
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    if as_run:
        schema = {"$schema": schema["$schema"], "$ref": "#/$defs/run", "$defs": schema["$defs"]}
    for error in sorted(jsonschema.Draft202012Validator(schema).iter_errors(data), key=str):
        path = "/".join(str(p) for p in error.absolute_path) or "(root)"
        problems.add("schema", f"{path}: {error.message}")
    return "checked"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("file", nargs="?", type=Path, default=HERE / "latest.json")
    parser.add_argument(
        "--run", action="store_true", help="the file is one harness run, not a combined latest.json"
    )
    parser.add_argument(
        "--allow-partial", action="store_true", help="accept fewer than three SDK runs"
    )
    parser.add_argument(
        "--require-schema", action="store_true", help="fail if jsonschema is not installed"
    )
    args = parser.parse_args()

    try:
        data = json.loads(args.file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"{args.file}: cannot read as JSON: {error}", file=sys.stderr)
        return 1

    problems = Problems()
    if args.run:
        check_run(problems, "run", data)
    else:
        check_combined(problems, data, args.allow_partial)
    schema_status = schema_check(problems, data, args.run, args.require_schema)

    if problems:
        print(f"{args.file}: INVALID", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print(f"{args.file}: valid (JSON Schema {schema_status})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
