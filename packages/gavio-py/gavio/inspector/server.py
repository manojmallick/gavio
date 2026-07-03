"""Embedded inspector HTTP server (F-DX-10) — stdlib only.

Serves the vendored single-file UI at ``/`` and a small JSON API under
``/api``. Runs a :class:`http.server.ThreadingHTTPServer` in a daemon thread
so it never blocks interpreter shutdown.

v0.7.0 adds the agentic and production endpoints: ``/api/dag``,
``/api/sessions``, ``/api/stats``, ``/api/simulate-cost``,
``/api/chain/verify``, ``/api/traces/{id}/export`` and ``POST /api/replay``.
"""

from __future__ import annotations

import asyncio
import json
import queue
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from typing import TYPE_CHECKING, Any
from urllib.parse import parse_qs, urlparse

from ..types import TokenUsage
from .analytics import build_dag, build_sessions, build_stats
from .export import EXPORT_FORMATS, export_trace

if TYPE_CHECKING:
    from .inspector import Inspector


def _load_ui() -> str:
    return resources.files("gavio.inspector").joinpath("ui.html").read_text(encoding="utf-8")


class _InspectorHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    stopping = False


class _Handler(BaseHTTPRequestHandler):
    inspector: Inspector  # set on the generated subclass

    # ---- plumbing -------------------------------------------------------------

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        pass  # never log request lines (may contain trace ids); stay quiet

    def _send(self, status: int, body: bytes, content_type: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Gavio-Inspector-Mode", self.inspector.mode)
        self.end_headers()
        self.wfile.write(body)

    def _send_json(self, status: int, payload: dict[str, Any]) -> None:
        self._send(status, json.dumps(payload, default=str).encode("utf-8"))

    def _authorized(self) -> bool:
        token = self.inspector.config.auth_token
        if not token:
            return True
        return self.headers.get("Authorization") == f"Bearer {token}"

    @staticmethod
    def _param(params: dict[str, list[str]], key: str) -> str | None:
        values = params.get(key)
        return values[0] if values else None

    # ---- routing ----------------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802 - http.server API
        try:
            if not self._authorized():
                self._send_json(401, {"error": "unauthorized"})
                return
            url = urlparse(self.path)
            path = url.path.rstrip("/") or "/"
            params = parse_qs(url.query)
            if path == "/":
                self._send(200, _load_ui().encode("utf-8"), "text/html; charset=utf-8")
            elif path == "/api/health":
                self._handle_health()
            elif path == "/api/pipeline":
                self._handle_pipeline()
            elif path == "/api/traces":
                self._handle_traces(params)
            elif path == "/api/dag":
                self._handle_dag(params)
            elif path == "/api/sessions":
                self._send_json(
                    200, {"sessions": build_sessions(self.inspector.buffer.summaries())}
                )
            elif path == "/api/stats":
                self._handle_stats(params)
            elif path == "/api/simulate-cost":
                self._handle_simulate_cost(params)
            elif path == "/api/chain/verify":
                self._handle_chain_verify()
            elif path.startswith("/api/traces/") and path.endswith("/export"):
                trace_id = path.removeprefix("/api/traces/").removesuffix("/export")
                self._handle_export(trace_id, params)
            elif path.startswith("/api/traces/"):
                self._handle_trace(path.removeprefix("/api/traces/"))
            elif path == "/api/stream":
                self._handle_stream()
            else:
                self._send_json(404, {"error": "not found"})
        except (BrokenPipeError, ConnectionResetError):
            pass  # client went away mid-response

    def do_POST(self) -> None:  # noqa: N802 - http.server API
        try:
            if not self._authorized():
                self._send_json(401, {"error": "unauthorized"})
                return
            path = urlparse(self.path).path.rstrip("/")
            if path == "/api/replay":
                self._handle_replay()
            else:
                self._send_json(404, {"error": "not found"})
        except (BrokenPipeError, ConnectionResetError):
            pass

    # ---- endpoints ------------------------------------------------------------

    def _handle_health(self) -> None:
        from gavio import __version__

        self._send_json(
            200,
            {
                "status": "ok",
                "version": __version__,
                "mode": self.inspector.mode,
                "sdk": "python",
                "traces": self.inspector.buffer.count(),
                "drops": self.inspector.bus.dropped,
            },
        )

    def _handle_pipeline(self) -> None:
        self._send_json(200, self.inspector.pipeline)

    def _handle_traces(self, params: dict[str, list[str]]) -> None:
        limit: int | None = None
        raw_limit = self._param(params, "limit")
        if raw_limit is not None:
            try:
                limit = int(raw_limit)
            except ValueError:
                limit = None
        summaries = self.inspector.buffer.summaries(limit)
        q = self._param(params, "q")
        if q:
            summaries = [
                s
                for s in summaries
                if any(
                    isinstance(s.get(field), str) and s[field].startswith(q)
                    for field in ("traceId", "promptHash", "responseHash")
                )
            ]
        self._send_json(200, {"traces": summaries})

    def _handle_trace(self, trace_id: str) -> None:
        trace = self.inspector.buffer.get(trace_id)
        if trace is None:
            self._send_json(404, {"error": "not found"})
        else:
            self._send_json(200, trace)

    def _handle_dag(self, params: dict[str, list[str]]) -> None:
        root = self._param(params, "root")
        session_id = self._param(params, "session_id")
        if root is None and session_id is None:
            self._send_json(400, {"error": "pass ?root=<trace_id> or ?session_id=<id>"})
            return
        dag = build_dag(self.inspector.buffer.summaries(), root=root, session_id=session_id)
        if dag is None:
            self._send_json(404, {"error": "not found"})
        else:
            self._send_json(200, dag)

    def _handle_stats(self, params: dict[str, list[str]]) -> None:
        try:
            stats = build_stats(
                self.inspector.buffer.summaries(),
                group_by=self._param(params, "group_by"),
                since=self._param(params, "since"),
            )
        except ValueError as error:
            self._send_json(400, {"error": str(error)})
            return
        self._send_json(200, stats)

    def _handle_simulate_cost(self, params: dict[str, list[str]]) -> None:
        trace_id = self._param(params, "trace_id")
        model = self._param(params, "model")
        if not trace_id or not model:
            self._send_json(400, {"error": "pass ?trace_id=<id>&model=<model>"})
            return
        trace = self.inspector.buffer.get(trace_id)
        if trace is None:
            self._send_json(404, {"error": "not found"})
            return
        summary = trace["summary"]
        usage_data = summary.get("usage")
        if not usage_data:
            self._send_json(400, {"error": "trace has no token usage"})
            return
        usage = TokenUsage(
            prompt_tokens=usage_data.get("promptTokens", 0),
            completion_tokens=usage_data.get("completionTokens", 0),
        )
        simulated = self.inspector.pricing.estimate(model, usage)
        original = summary.get("costUsd") or 0.0
        self._send_json(
            200,
            {
                "traceId": trace_id,
                "model": summary.get("model"),
                "costUsd": original,
                "simulatedModel": model,
                "simulatedCostUsd": simulated,
                "deltaUsd": round(simulated - original, 8),
                "usage": usage_data,
            },
        )

    def _handle_chain_verify(self) -> None:
        records = self.inspector.audit_records
        if records is None:
            self._send_json(
                400,
                {
                    "error": "chain verification requires an audit store; "
                    "run: gavio inspect --store <audit.jsonl>"
                },
            )
            return
        from .store import verify_chain_records

        intact, first_broken = verify_chain_records(records)
        self._send_json(
            200,
            {"intact": intact, "records": len(records), "firstBrokenTraceId": first_broken},
        )

    def _handle_export(self, trace_id: str, params: dict[str, list[str]]) -> None:
        if self.inspector.mode == "metadata":
            self._send_json(403, {"error": "export requires full or redacted capture mode"})
            return
        format_ = self._param(params, "format")
        if format_ not in EXPORT_FORMATS:
            self._send_json(400, {"error": f"format must be one of {list(EXPORT_FORMATS)}"})
            return
        trace = self.inspector.buffer.get(trace_id)
        if trace is None:
            self._send_json(404, {"error": "not found"})
            return
        try:
            content_type, body = export_trace(trace, format_)
        except ValueError as error:
            self._send_json(400, {"error": str(error)})
            return
        self._send(200, body.encode("utf-8"), content_type)

    def _handle_replay(self) -> None:
        if self.inspector.mode != "full":
            self._send_json(403, {"error": "replay requires full capture mode"})
            return
        handler = self.inspector.replay_handler
        if handler is None:
            self._send_json(403, {"error": "no live gateway attached; replay unavailable"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self._send_json(400, {"error": "invalid JSON body"})
            return
        trace_id = body.get("traceId")
        if not trace_id:
            self._send_json(400, {"error": "body must include traceId"})
            return
        trace = self.inspector.buffer.get(trace_id)
        if trace is None:
            self._send_json(404, {"error": "not found"})
            return
        overrides = body.get("overrides") or {}
        messages = overrides.get("messages")
        if messages is None:
            start = next((e for e in trace["events"] if e["type"] == "trace.start"), None)
            messages = (start or {}).get("data", {}).get("messages")
        if not messages:
            self._send_json(400, {"error": "trace has no captured messages to replay"})
            return
        model = overrides.get("model") or trace["summary"].get("model")
        options = overrides.get("options") or {}
        try:
            # The handler thread has no event loop; the replayed call runs the
            # full interceptor chain — PII guard included, never bypassed.
            response = asyncio.run(
                handler(
                    messages=messages,
                    model=model,
                    metadata={"replay_of": trace_id},
                    **options,
                )
            )
        except Exception as error:  # noqa: BLE001 - surfaced to the caller
            self._send_json(
                502, {"error": f"{type(error).__name__}: {error}", "replayOf": trace_id}
            )
            return
        self._send_json(200, {"traceId": response.trace_id, "replayOf": trace_id})

    def _handle_stream(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Gavio-Inspector-Mode", self.inspector.mode)
        self.end_headers()

        events: queue.Queue[dict[str, Any]] = queue.Queue()
        self.inspector.bus.subscribe(events.put)
        try:
            while not self.server.stopping:  # type: ignore[attr-defined]
                try:
                    event = events.get(timeout=0.5)
                except queue.Empty:
                    continue
                self.wfile.write(f"data: {json.dumps(event, default=str)}\n\n".encode())
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass  # client disconnected
        finally:
            self.inspector.bus.unsubscribe(events.put)


class InspectorServer:
    """Owns the HTTP server thread. ``port`` is the actual bound port."""

    def __init__(self, inspector: Inspector) -> None:
        config = inspector.config
        handler = type("BoundHandler", (_Handler,), {"inspector": inspector})
        server_cls = _InspectorHTTPServer
        if ":" in config.bind:  # IPv6 loopback
            server_cls = type(
                "_InspectorHTTPServer6",
                (_InspectorHTTPServer,),
                {"address_family": socket.AF_INET6},
            )
        self._httpd = server_cls((config.bind, config.port), handler)
        self.port: int = self._httpd.server_address[1]
        self._thread = threading.Thread(
            target=self._httpd.serve_forever,
            name=f"gavio-inspector:{self.port}",
            daemon=True,
        )

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._httpd.stopping = True
        self._httpd.shutdown()
        self._httpd.server_close()
