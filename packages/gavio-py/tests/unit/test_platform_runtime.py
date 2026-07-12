from __future__ import annotations

import json
from pathlib import Path

from gavio import build_platform_runtime_profile, verify_platform_runtime_profile

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


def _vector() -> dict:
    return json.loads((_VECTORS / "platform-runtime" / "profile.json").read_text())


def _build_from_vector(data: dict) -> dict:
    return build_platform_runtime_profile(
        profile_id=data["profileId"],
        generated_at=data["generatedAt"],
        sdk=data["sdk"],
        runtime=data["runtime"],
        surfaces=data["surfaces"],
        exporters=data.get("exporters"),
        integrations=data.get("integrations"),
        controls=data.get("controls"),
        evidence=data.get("evidence"),
        required_surfaces=data.get("requiredSurfaces"),
    )


def test_builds_shared_platform_runtime_profile() -> None:
    vector = _vector()
    profile = _build_from_vector(vector["readyProfileInput"])

    assert profile == vector["readyProfile"]
    result = verify_platform_runtime_profile(profile)
    assert result.valid
    assert result.errors == []
    assert result.computed_hash == profile["profileHash"]


def test_reports_platform_runtime_readiness_gaps() -> None:
    vector = _vector()
    profile = _build_from_vector(vector["gapCase"]["input"])

    assert profile["readiness"] == vector["gapCase"]["expectedReadiness"]
    assert not profile["readiness"]["ready"]


def test_rejects_tampered_or_content_bearing_profile() -> None:
    profile = _build_from_vector(_vector()["readyProfileInput"])
    profile["runtime"]["eventExportMode"] = "full_local_debug"
    profile["runtime"]["rawPrompt"] = "do not store me"

    result = verify_platform_runtime_profile(profile)

    assert not result.valid
    assert "profileHash does not match profile content" in result.errors
    assert "profile contains content-bearing keys" in result.errors
    assert "readiness does not match profile content" in result.errors
