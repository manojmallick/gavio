"""Self-hosted control-plane runtime config client.

The client is intentionally stdlib-only. It fetches metadata-only runtime
configuration from a self-hosted Gavio control plane and writes the last good
response to a local cache so applications can fail open during outages.
"""

from __future__ import annotations

import hashlib
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from copy import deepcopy
from pathlib import Path
from typing import Any, Literal

FailMode = Literal["open", "closed"]


class ControlPlaneError(RuntimeError):
    """Raised when control-plane config cannot be loaded in fail-closed mode."""


class ControlPlaneClient:
    """Fetch and cache runtime configuration from the Gavio control plane."""

    def __init__(
        self,
        url: str,
        runtime_key: str,
        policy_source: str,
        *,
        cache_path: str | Path | None = None,
        fail_mode: FailMode = "open",
        timeout_seconds: float = 2.0,
    ) -> None:
        if fail_mode not in ("open", "closed"):
            raise ValueError("fail_mode must be 'open' or 'closed'")
        self.url = url.rstrip("/")
        self.runtime_key = runtime_key
        self.policy_source = policy_source
        self.fail_mode: FailMode = fail_mode
        self.timeout_seconds = timeout_seconds
        self.cache_path = (
            Path(cache_path) if cache_path else _default_cache_path(url, policy_source)
        )

    def load_config(self) -> dict[str, Any]:
        """Return the current runtime config, cached config, or unavailable shell."""

        try:
            config = self._fetch()
        except Exception as exc:  # noqa: BLE001 - surfaced in fail-closed mode
            cached = self._read_cache()
            if cached is not None:
                cached.setdefault("cache", {})["loadedFrom"] = "cache"
                return cached
            if self.fail_mode == "closed":
                raise ControlPlaneError(
                    f"failed to load control-plane config for {self.policy_source}: {exc}"
                ) from exc
            return unavailable_config(self.policy_source, self.fail_mode)
        config.setdefault("cache", {})["loadedFrom"] = "control_plane"
        self._write_cache(config)
        return config

    def _fetch(self) -> dict[str, Any]:
        query = urllib.parse.urlencode(
            {"policy_source": self.policy_source, "fail_mode": self.fail_mode}
        )
        request = urllib.request.Request(
            f"{self.url}/api/runtime/config?{query}",
            headers={"Authorization": f"Bearer {self.runtime_key}"},
        )
        with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if not isinstance(payload, dict):
            raise ControlPlaneError("control-plane response must be a JSON object")
        return payload

    def _read_cache(self) -> dict[str, Any] | None:
        try:
            payload = json.loads(self.cache_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return None
        return deepcopy(payload) if isinstance(payload, dict) else None

    def _write_cache(self, config: dict[str, Any]) -> None:
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(
            json.dumps(config, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )


def load_control_plane_config(
    url: str,
    runtime_key: str,
    policy_source: str,
    *,
    cache_path: str | Path | None = None,
    fail_mode: FailMode = "open",
    timeout_seconds: float = 2.0,
) -> dict[str, Any]:
    """Convenience wrapper around :class:`ControlPlaneClient`."""

    return ControlPlaneClient(
        url,
        runtime_key,
        policy_source,
        cache_path=cache_path,
        fail_mode=fail_mode,
        timeout_seconds=timeout_seconds,
    ).load_config()


def unavailable_config(policy_source: str, fail_mode: FailMode = "open") -> dict[str, Any]:
    """Return a metadata-only placeholder when fail-open has no cache."""

    return {
        "schemaVersion": "1.0",
        "configVersion": "unavailable",
        "projectId": "",
        "environment": "",
        "policySource": policy_source,
        "policy": {"id": "unavailable", "name": "unavailable", "rules": []},
        "budgets": [],
        "rollout": {"id": "unavailable", "policyId": "unavailable", "status": "paused"},
        "cache": {"ttlSeconds": 0, "failMode": fail_mode, "loadedFrom": "unavailable"},
    }


def _default_cache_path(url: str, policy_source: str) -> Path:
    root = Path(os.environ.get("GAVIO_CACHE_DIR", Path.home() / ".cache" / "gavio"))
    digest = hashlib.sha256(f"{url}|{policy_source}".encode()).hexdigest()[:16]
    return root / "control-plane" / f"{digest}.json"
