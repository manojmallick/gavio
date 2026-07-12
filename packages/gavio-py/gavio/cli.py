"""gavio CLI — ``gavio inspect --store`` serves the read-only dashboard (F-DX-08)."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="gavio", description="Gavio command-line tools.")
    subcommands = parser.add_subparsers(dest="command", required=True)

    inspect = subcommands.add_parser(
        "inspect",
        help="Serve the Inspector dashboard over a persisted audit store (metadata mode).",
    )
    inspect.add_argument(
        "--store",
        required=True,
        metavar="PATH",
        help="JSONL audit store written by JsonlSink (audit sink 'jsonl://<path>').",
    )
    inspect.add_argument("--port", type=int, default=7411)
    inspect.add_argument("--bind", default="127.0.0.1")
    inspect.add_argument(
        "--token", default=None, help="Bearer token (required for non-loopback binds)."
    )

    cost = subcommands.add_parser("cost", help="Cost Governance reporting tools.")
    cost_subcommands = cost.add_subparsers(dest="cost_command", required=True)
    report = cost_subcommands.add_parser(
        "report", help="Generate a Cost Governance v2 report from JSONL records."
    )
    report.add_argument(
        "--audit",
        required=True,
        metavar="PATH",
        help="JSONL audit/runtime summary file.",
    )
    report.add_argument("--group-by", default=None)
    report.add_argument("--since", default=None)
    report.add_argument(
        "--budget-policy",
        action="append",
        default=[],
        metavar="PATH",
        help="Budget policy JSON file. May be repeated.",
    )
    report.add_argument("--usage-elapsed-ratio", type=float, default=1.0)
    report.add_argument("--pretty", action="store_true")

    args = parser.parse_args(argv)
    if args.command == "inspect":
        return _inspect(args)
    if args.command == "cost" and args.cost_command == "report":
        return _cost_report(args)
    return 2


def _inspect(args: argparse.Namespace) -> int:
    from .inspector.store import open_store

    try:
        inspector = open_store(args.store, port=args.port, bind=args.bind, auth_token=args.token)
    except (OSError, ValueError) as error:
        print(f"gavio inspect: {error}", file=sys.stderr)
        return 1
    inspector.start_server()
    print(
        f"Gavio Inspector (metadata mode) — {inspector.buffer.count()} traces from {args.store}\n"
        f"  http://{args.bind}:{inspector.server.port}/",
        flush=True,
    )
    try:
        import threading

        threading.Event().wait()
    except KeyboardInterrupt:
        inspector.stop()
    return 0


def _cost_report(args: argparse.Namespace) -> int:
    from .inspector.store import load_records, summary_from_record
    from .interceptors.governance import BudgetPolicy, build_cost_governance_report

    try:
        records = load_records(args.audit)
        summaries = [_summary_from_jsonl_record(record, summary_from_record) for record in records]
        policies = _load_budget_policies(args.budget_policy, BudgetPolicy)
        report = build_cost_governance_report(
            summaries,
            policies=policies,
            group_by=args.group_by,
            since=args.since,
            usage_elapsed_ratio=args.usage_elapsed_ratio,
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"gavio cost report: {error}", file=sys.stderr)
        return 1
    indent = 2 if args.pretty else None
    print(json.dumps(report, indent=indent, sort_keys=True))
    return 0


def _summary_from_jsonl_record(
    record: dict[str, Any], summary_from_record: Any
) -> dict[str, Any]:
    if "traceId" in record or "costUsd" in record:
        return dict(record)
    summary = summary_from_record(record)
    dimensions = record.get("cost_dimensions") or record.get("costDimensions") or {}
    if isinstance(dimensions, dict):
        summary["costDimensions"] = dimensions
        for key in (
            "tenant",
            "team",
            "feature",
            "user",
            "endpoint",
            "environment",
            "workflow",
            "tool",
        ):
            value = dimensions.get(key)
            if value not in (None, ""):
                summary[key] = value
    return summary


def _load_budget_policies(paths: list[str], budget_policy_cls: Any) -> list[Any]:
    policies = []
    for path in paths:
        data = json.loads(Path(path).expanduser().read_text(encoding="utf-8"))
        if isinstance(data, list):
            policies.extend(budget_policy_cls.from_dict(item) for item in data)
        elif isinstance(data, dict) and isinstance(data.get("policies"), list):
            policies.extend(budget_policy_cls.from_dict(item) for item in data["policies"])
        elif isinstance(data, dict):
            policies.append(budget_policy_cls.from_dict(data))
        else:
            raise ValueError(f"{path}: expected a policy object, policies array, or list")
    return policies


if __name__ == "__main__":
    raise SystemExit(main())
