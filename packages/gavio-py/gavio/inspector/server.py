"""Embedded inspector HTTP server (F-DX-10) — stdlib only.

Serves the vendored single-file UI at ``/`` and a small JSON API under
``/api``. Runs a :class:`http.server.ThreadingHTTPServer` in a daemon thread
so it never blocks interpreter shutdown.
"""

from __future__ import annotations

import json
import queue
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from typing import TYPE_CHECKING, Any
from urllib.parse import parse_qs, urlparse

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

    # ---- routing ----------------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802 - http.server API
        try:
            if not self._authorized():
                self._send_json(401, {"error": "unauthorized"})
                return
            url = urlparse(self.path)
            path = url.path.rstrip("/") or "/"
            if path == "/":
                self._send(200, _load_ui().encode("utf-8"), "text/html; charset=utf-8")
            elif path == "/api/health":
                self._handle_health()
            elif path == "/api/pipeline":
                self._handle_pipeline()
            elif path == "/api/traces":
                self._handle_traces(url.query)
            elif path.startswith("/api/traces/"):
                self._handle_trace(path.removeprefix("/api/traces/"))
            elif path == "/api/stream":
                self._handle_stream()
            else:
                self._send_json(404, {"error": "not found"})
        except (BrokenPipeError, ConnectionResetError):
            pass  # client went away mid-response

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

    def _handle_traces(self, query: str) -> None:
        params = parse_qs(query)
        limit: int | None = None
        if "limit" in params:
            try:
                limit = int(params["limit"][0])
            except ValueError:
                limit = None
        self._send_json(200, {"traces": self.inspector.buffer.summaries(limit)})

    def _handle_trace(self, trace_id: str) -> None:
        trace = self.inspector.buffer.get(trace_id)
        if trace is None:
            self._send_json(404, {"error": "not found"})
        else:
            self._send_json(200, trace)

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
