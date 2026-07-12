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

    events = subcommands.add_parser("events", help="Runtime event conversion tools.")
    events_subcommands = events.add_subparsers(dest="events_command", required=True)
    convert = events_subcommands.add_parser(
        "convert", help="Convert runtime event JSONL into integration-friendly formats."
    )
    convert.add_argument(
        "--from",
        dest="from_path",
        required=True,
        metavar="PATH",
        help="Runtime event JSONL file written by a Gavio runtime exporter.",
    )
    convert.add_argument(
        "--to",
        dest="to_format",
        choices=["otel-json"],
        required=True,
        help="Output format.",
    )
    convert.add_argument("--service-name", default="gavio")

    policy = subcommands.add_parser("policy", help="Policy Pack catalog tools.")
    policy_subcommands = policy.add_subparsers(dest="policy_command", required=True)
    policy_subcommands.add_parser("list", help="List Policy Packs in the catalog.")
    validate = policy_subcommands.add_parser(
        "validate", help="Load a Policy Pack and verify its manifest signature."
    )
    validate.add_argument("path_or_name", metavar="PATH_OR_NAME")
    sign = policy_subcommands.add_parser(
        "sign", help="Print the canonical SHA-256 signature value for a Policy Pack."
    )
    sign.add_argument("path_or_name", metavar="PATH_OR_NAME")

    eval_cmd = subcommands.add_parser("eval", help="Prompt eval runner and CI gates.")
    eval_subcommands = eval_cmd.add_subparsers(dest="eval_command", required=True)
    run = eval_subcommands.add_parser(
        "run", help="Run a deterministic prompt eval suite from JSON/YAML."
    )
    run.add_argument("suite", metavar="SUITE")
    run.add_argument(
        "--templates",
        action="append",
        default=[],
        metavar="PATH",
        help="Prompt template JSON/YAML file. May be repeated.",
    )
    run.add_argument("--fail-under", type=float, default=None)
    run.add_argument("--baseline", default=None, metavar="PATH")
    run.add_argument("--max-regression", type=float, default=0.0)
    run.add_argument("--report", default=None, metavar="PATH", help="Write JSON report.")
    run.add_argument("--junit", default=None, metavar="PATH", help="Write JUnit XML report.")
    run.add_argument("--pretty", action="store_true")
    run.add_argument(
        "--summary",
        action="store_true",
        help="Print a compact summary instead of the full JSON report.",
    )

    workflow = subcommands.add_parser("workflow", help="Platform workflow release tools.")
    workflow_subcommands = workflow.add_subparsers(dest="workflow_command", required=True)
    release = workflow_subcommands.add_parser(
        "release",
        help="Build a metadata-only platform workflow release artifact.",
    )
    release.add_argument("manifest", metavar="MANIFEST")
    release.add_argument("--output", default=None, metavar="PATH", help="Write JSON artifact.")
    release.add_argument("--pretty", action="store_true")
    release.add_argument(
        "--allow-failures",
        action="store_true",
        help="Return success even when workflow gates fail.",
    )

    args = parser.parse_args(argv)
    if args.command == "inspect":
        return _inspect(args)
    if args.command == "cost" and args.cost_command == "report":
        return _cost_report(args)
    if args.command == "events" and args.events_command == "convert":
        return _events_convert(args)
    if args.command == "policy":
        return _policy(args)
    if args.command == "eval" and args.eval_command == "run":
        return _eval_run(args)
    if args.command == "workflow" and args.workflow_command == "release":
        return _workflow_release(args)
    return 2


def _inspect(args: argparse.Namespace) -> int:
    from .inspector.store import open_store

    try:
        inspector = open_store(args.store, port=args.port, bind=args.bind, auth_token=args.token)
    except (OSError, ValueError) as error:
        print(f"gavio inspect: {error}", file=sys.stderr)
        return 1
    inspector.start_server()
    assert inspector.server is not None
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


def _events_convert(args: argparse.Namespace) -> int:
    from .exporters import otel_spans_from_events

    try:
        events = _load_jsonl(args.from_path)
        if args.to_format == "otel-json":
            for span in otel_spans_from_events(events, service_name=args.service_name):
                print(json.dumps(span, separators=(",", ":"), sort_keys=True))
            return 0
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"gavio events convert: {error}", file=sys.stderr)
        return 1
    return 2


def _policy(args: argparse.Namespace) -> int:
    from .interceptors.pii import PolicyPack, list_policy_packs, load_policy_pack

    try:
        if args.policy_command == "list":
            for name in list_policy_packs():
                print(name)
            return 0
        pack = _load_policy_pack_arg(args.path_or_name, PolicyPack, load_policy_pack)
        if args.policy_command == "validate":
            if not pack.verify_signature():
                print(f"gavio policy validate: invalid signature for {pack.id}", file=sys.stderr)
                return 1
            print(f"ok {pack.id} {pack.version}")
            return 0
        if args.policy_command == "sign":
            print(pack.signature_value())
            return 0
    except (OSError, ValueError, FileNotFoundError, json.JSONDecodeError) as error:
        print(f"gavio policy {args.policy_command}: {error}", file=sys.stderr)
        return 1
    return 2


def _eval_run(args: argparse.Namespace) -> int:
    from .prompts import cli_summary, run_eval_file, write_json_report, write_junit_report
    from .prompts.runner import error_exit, exit_code, print_json

    try:
        result = run_eval_file(
            args.suite,
            template_paths=args.templates,
            fail_under=args.fail_under,
            baseline_path=args.baseline,
            max_regression=args.max_regression,
        )
        if args.report:
            write_json_report(result, args.report, pretty=args.pretty)
        if args.junit:
            write_junit_report(result, args.junit)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        return error_exit("gavio eval run", error)
    print_json(cli_summary(result) if args.summary else result.to_dict(), pretty=args.pretty)
    return exit_code(result)


def _workflow_release(args: argparse.Namespace) -> int:
    from .prompts.runner import error_exit, print_json
    from .workflow import run_platform_workflow_release_file

    try:
        result = run_platform_workflow_release_file(args.manifest)
        if args.output:
            indent = 2 if args.pretty else None
            Path(args.output).expanduser().write_text(
                json.dumps(result.artifact, indent=indent, sort_keys=True) + "\n",
                encoding="utf-8",
            )
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        return error_exit("gavio workflow release", error)
    print_json(result.artifact, pretty=args.pretty)
    return 0 if result.passed or args.allow_failures else 1


def _load_policy_pack_arg(path_or_name: str, policy_pack_cls: Any, load_by_name: Any) -> Any:
    path = Path(path_or_name).expanduser()
    if path.exists():
        return policy_pack_cls.load_path(path)
    return load_by_name(path_or_name)


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
    policies: list[Any] = []
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


def _load_jsonl(path: str) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    lines = Path(path).expanduser().read_text(encoding="utf-8").splitlines()
    for line_no, line in enumerate(lines, 1):
        text = line.strip()
        if not text:
            continue
        record = json.loads(text)
        if not isinstance(record, dict):
            raise ValueError(f"{path}:{line_no}: expected JSON object")
        records.append(record)
    return records


if __name__ == "__main__":
    raise SystemExit(main())
