"""gavio CLI — ``gavio inspect --store`` serves the read-only dashboard (F-DX-08)."""

from __future__ import annotations

import argparse
import sys


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

    args = parser.parse_args(argv)
    if args.command == "inspect":
        return _inspect(args)
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


if __name__ == "__main__":
    raise SystemExit(main())
