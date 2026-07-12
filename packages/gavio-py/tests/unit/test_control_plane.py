from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Any

import pytest

from gavio import Gateway
from gavio.control_plane import ControlPlaneClient, ControlPlaneError

CONFIG = {
    "schemaVersion": "1.0",
    "configVersion": "cfg_test",
    "projectId": "proj_support",
    "environment": "prod",
    "policySource": "project:prod-support",
    "policy": {"id": "pol_support", "name": "Support", "policyPack": "support", "rules": []},
    "budgets": [{"id": "budget_support", "scopeType": "project", "limitUsd": 25}],
    "rollout": {"id": "rollout_support", "policyId": "pol_support", "status": "active"},
    "cache": {"ttlSeconds": 120, "failMode": "open"},
}


def test_control_plane_client_fetches_and_falls_back_to_cache(tmp_path: Path) -> None:
    server, url = _serve_config(CONFIG)
    cache = tmp_path / "control-plane.json"
    try:
        client = ControlPlaneClient(url, "gav_rt_test", "project:prod-support", cache_path=cache)
        first = client.load_config()
        assert first["cache"]["loadedFrom"] == "control_plane"
        assert first["policy"]["policyPack"] == "support"
    finally:
        server.shutdown()
        server.server_close()

    cached = client.load_config()
    assert cached["cache"]["loadedFrom"] == "cache"
    assert cached["policySource"] == "project:prod-support"


def test_control_plane_client_fail_closed_without_cache() -> None:
    client = ControlPlaneClient(
        "http://127.0.0.1:1",
        "gav_rt_test",
        "project:prod-support",
        fail_mode="closed",
        timeout_seconds=0.05,
    )
    with pytest.raises(ControlPlaneError):
        client.load_config()


def test_gateway_builder_loads_control_plane_config(tmp_path: Path) -> None:
    server, url = _serve_config(CONFIG)
    try:
        gateway = (
            Gateway.builder()
            .dev_mode(True)
            .control_plane(
                url,
                "gav_rt_test",
                "project:prod-support",
                cache_path=str(tmp_path / "gateway-cache.json"),
            )
            .build()
        )
    finally:
        server.shutdown()
        server.server_close()

    assert gateway.control_plane_config is not None
    assert gateway.control_plane_config["projectId"] == "proj_support"


def _serve_config(config: dict[str, Any]) -> tuple[HTTPServer, str]:
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            assert self.path.startswith("/api/runtime/config?")
            assert self.headers.get("Authorization") == "Bearer gav_rt_test"
            body = json.dumps(config).encode("utf-8")
            self.send_response(200)
            self.send_header("content-type", "application/json")
            self.send_header("content-length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
            return None

    server = HTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    host, port = server.server_address
    return server, f"http://{host}:{port}"
