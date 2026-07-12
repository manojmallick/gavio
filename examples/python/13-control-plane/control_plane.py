"""Gavio self-hosted control-plane runtime config.

    pip install -r requirements.txt
    python control_plane.py
"""

import os

from gavio import Gateway


def main() -> None:
    gateway = (
        Gateway.builder()
        .dev_mode(True)
        .control_plane(
            os.getenv("GAVIO_CONTROL_PLANE_URL", "http://127.0.0.1:8787"),
            os.getenv("GAVIO_RUNTIME_KEY", "gav_rt_missing"),
            os.getenv("GAVIO_POLICY_SOURCE", "project:prod-support"),
            cache_path=".gavio-control-plane-cache.json",
            fail_mode="open",
            timeout_seconds=0.2,
        )
        .build()
    )
    config = gateway.control_plane_config or {}
    print("source :", config.get("cache", {}).get("loadedFrom"))
    print("policy :", config.get("policySource"))
    print("project:", config.get("projectId") or "(not loaded)")


if __name__ == "__main__":
    main()
