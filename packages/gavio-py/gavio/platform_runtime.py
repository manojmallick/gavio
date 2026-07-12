"""Platform-grade runtime profile helpers.

The Platform Runtime Profile is a metadata-only readiness contract for
production Gavio deployments. It summarizes which runtime surfaces are enabled,
which controls supplied evidence, and which deterministic gaps remain before a
deployment should be called platform-grade.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from importlib.metadata import PackageNotFoundError, version
from typing import Any

SCHEMA_VERSION = "2.0"

DEFAULT_REQUIRED_SURFACES = (
    "runtime_events",
    "audit_hashes",
    "policy_packs",
    "cost_governance",
    "tool_runtime",
    "trust_evidence",
)

CONTENT_KEY_NAMES = {
    "messages",
    "content",
    "diff",
    "rawmessages",
    "rawprompt",
    "rawresponse",
    "prompttext",
    "responsetext",
    "inputtext",
    "outputtext",
    "rawinput",
    "rawoutput",
}


@dataclass(frozen=True)
class PlatformRuntimeVerification:
    """Verification result for a Platform Runtime Profile."""

    valid: bool
    errors: list[str]
    computed_hash: str
    readiness: dict[str, Any]


def build_platform_runtime_profile(
    *,
    profile_id: str,
    generated_at: str,
    runtime: Mapping[str, Any],
    surfaces: Sequence[str],
    exporters: Sequence[str] | None = None,
    integrations: Sequence[str] | None = None,
    controls: Sequence[Mapping[str, Any]] | None = None,
    evidence: Mapping[str, Any] | None = None,
    sdk: Mapping[str, Any] | None = None,
    required_surfaces: Sequence[str] | None = None,
) -> dict[str, Any]:
    """Build a deterministic metadata-only Platform Runtime Profile."""

    requirements = {
        "requiredSurfaces": _unique_sorted(required_surfaces or DEFAULT_REQUIRED_SURFACES)
    }
    profile: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "profileId": profile_id,
        "generatedAt": generated_at,
        "sdk": dict(sdk or {"name": "gavio-python", "version": _installed_version()}),
        "runtime": dict(runtime),
        "surfaces": _unique_sorted(surfaces),
        "exporters": _unique_sorted(exporters or ()),
        "integrations": _unique_sorted(integrations or ()),
        "controls": [dict(control) for control in controls or ()],
        "evidence": _default_evidence(evidence),
        "requirements": requirements,
    }
    profile["readiness"] = platform_runtime_readiness(profile)
    profile["profileHash"] = platform_profile_hash(profile)
    return profile


def verify_platform_runtime_profile(profile: Mapping[str, Any]) -> PlatformRuntimeVerification:
    """Verify profile integrity, privacy posture, and readiness consistency."""

    computed_hash = platform_profile_hash(profile)
    readiness = platform_runtime_readiness(profile)
    errors: list[str] = []

    if profile.get("schemaVersion") != SCHEMA_VERSION:
        errors.append("schemaVersion must be 2.0")
    if profile.get("profileHash") != computed_hash:
        errors.append("profileHash does not match profile content")
    if _contains_content_keys(profile):
        errors.append("profile contains content-bearing keys")
    if profile.get("readiness") != readiness:
        errors.append("readiness does not match profile content")

    return PlatformRuntimeVerification(
        valid=not errors,
        errors=errors,
        computed_hash=computed_hash,
        readiness=readiness,
    )


def platform_profile_hash(profile: Mapping[str, Any]) -> str:
    """Return ``sha256:<hex>`` for a profile with ``profileHash`` excluded."""

    canonical = _canonical_json(_without_profile_hash(profile))
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


def platform_runtime_readiness(profile: Mapping[str, Any]) -> dict[str, Any]:
    """Compute deterministic readiness gaps for a Platform Runtime Profile."""

    requirements = _as_mapping(profile.get("requirements"))
    required = _unique_sorted(requirements.get("requiredSurfaces") or DEFAULT_REQUIRED_SURFACES)
    surfaces = set(_string_list(profile.get("surfaces")))
    runtime = _as_mapping(profile.get("runtime"))
    evidence = _as_mapping(profile.get("evidence"))
    runtime_events = _as_mapping(evidence.get("runtimeEvents"))
    audit_chain = _as_mapping(evidence.get("auditChain"))
    controls = [control for control in profile.get("controls", []) if isinstance(control, Mapping)]

    gaps: list[dict[str, str]] = []
    for surface in required:
        if surface not in surfaces:
            gaps.append(
                _gap(
                    f"missing_surface:{surface}",
                    f"required surface {surface} is not enabled",
                )
            )
    if runtime.get("eventExportMode") != "metadata_only":
        gaps.append(
            _gap(
                "runtime.event_export_mode",
                "runtime.eventExportMode must be metadata_only",
            )
        )
    if runtime_events.get("contentFree") is not True:
        gaps.append(
            _gap(
                "runtime_events.content_free",
                "runtime event evidence must be content-free",
            )
        )
    if audit_chain.get("verified") is not True:
        gaps.append(
            _gap(
                "audit_chain.verified",
                "audit-chain evidence must be verified",
            )
        )
    for control in controls:
        if control.get("status") == "fail":
            control_id = str(control.get("id") or "unknown")
            gaps.append(_gap(f"control_failed:{control_id}", f"control {control_id} failed"))

    total_checks = max(1, len(required) + 3 + len(controls))
    score = max(0, round(100 * (total_checks - len(gaps)) / total_checks))
    return {
        "ready": len(gaps) == 0,
        "score": score,
        "requiredSurfaces": required,
        "gaps": gaps,
    }


def _default_evidence(evidence: Mapping[str, Any] | None) -> dict[str, Any]:
    data = dict(evidence or {})
    data.setdefault("auditChain", {"recordCount": 0, "verified": False})
    data.setdefault("runtimeEvents", {"eventCount": 0, "contentFree": False})
    return data


def _gap(code: str, message: str) -> dict[str, str]:
    return {"code": code, "severity": "error", "message": message}


def _unique_sorted(values: Sequence[Any]) -> list[str]:
    return sorted({str(value) for value in values})


def _string_list(value: Any) -> list[str]:
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [str(item) for item in value]
    return []


def _canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _without_profile_hash(profile: Mapping[str, Any]) -> dict[str, Any]:
    data = dict(profile)
    data.pop("profileHash", None)
    return data


def _contains_content_keys(value: Any) -> bool:
    if isinstance(value, Mapping):
        for key, nested in value.items():
            normalized = str(key).replace("_", "").replace("-", "").lower()
            if normalized in CONTENT_KEY_NAMES:
                return True
            if _contains_content_keys(nested):
                return True
    elif isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return any(_contains_content_keys(item) for item in value)
    return False


def _as_mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _installed_version() -> str:
    try:
        return version("gavio")
    except PackageNotFoundError:
        return "1.9.0"
