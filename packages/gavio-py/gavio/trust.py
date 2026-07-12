"""Production trust package helpers.

These helpers create metadata-only evidence bundles for release reviews. The
bundle deliberately stores hashes, counters, statuses, and document pointers
instead of raw prompt, response, tool input, or tool output content.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from importlib.metadata import PackageNotFoundError, version
from typing import Any

from .interceptors.audit import AuditRecord, verify_chain

SCHEMA_VERSION = "1.0"
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
class TrustBundleVerification:
    """Verification result for a Production Trust Bundle."""

    valid: bool
    errors: list[str]
    computed_hash: str


def build_production_trust_bundle(
    *,
    bundle_id: str,
    release: Mapping[str, Any],
    runtime: Mapping[str, Any],
    generated_at: str,
    sdk: Mapping[str, Any] | None = None,
    audit_records: Sequence[AuditRecord] | None = None,
    audit_chain_verified: bool | None = None,
    runtime_events: Sequence[Mapping[str, Any]] | None = None,
    controls: Sequence[Mapping[str, Any]] | None = None,
    documents: Sequence[Mapping[str, Any]] | None = None,
    privacy: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Build a deterministic, metadata-only Production Trust Bundle.

    ``generated_at`` is explicit by design so release tooling can create a
    reproducible bundle when it needs one.
    """

    records = list(audit_records or [])
    events = [dict(event) for event in runtime_events or []]
    controls_list = [dict(control) for control in controls or []]
    documents_list = [dict(document) for document in documents or []]

    if audit_chain_verified is None:
        audit_chain_verified = verify_chain(records)

    bundle: dict[str, Any] = {
        "schemaVersion": SCHEMA_VERSION,
        "bundleId": bundle_id,
        "generatedAt": generated_at,
        "sdk": dict(sdk or {"name": "gavio-python", "version": _installed_version()}),
        "release": dict(release),
        "runtime": dict(runtime),
        "privacy": dict(
            privacy
            or {
                "contentMode": "metadata_only",
                "containsRawContent": False,
                "redactedFields": ["messages", "content", "diff"],
            }
        ),
        "evidence": {
            "auditChain": _audit_chain_summary(records, audit_chain_verified),
            "runtimeEvents": {
                "eventCount": len(events),
                "contentFree": not _contains_content_keys(events),
                "eventTypes": sorted(
                    {
                        str(event.get("type"))
                        for event in events
                        if event.get("type") is not None
                    }
                ),
            },
            "controls": controls_list,
        },
        "documents": documents_list,
    }
    bundle["bundleHash"] = trust_bundle_hash(bundle)
    return bundle


def verify_production_trust_bundle(bundle: Mapping[str, Any]) -> TrustBundleVerification:
    """Verify bundle integrity and privacy posture."""

    computed_hash = trust_bundle_hash(bundle)
    errors: list[str] = []

    if bundle.get("schemaVersion") != SCHEMA_VERSION:
        errors.append("schemaVersion must be 1.0")
    if bundle.get("bundleHash") != computed_hash:
        errors.append("bundleHash does not match bundle content")
    if _contains_content_keys(bundle):
        errors.append("bundle contains content-bearing keys")

    privacy = _as_mapping(bundle.get("privacy"))
    if privacy.get("contentMode") != "metadata_only":
        errors.append("privacy.contentMode must be metadata_only")
    if privacy.get("containsRawContent") is not False:
        errors.append("privacy.containsRawContent must be false")

    evidence = _as_mapping(bundle.get("evidence"))
    audit_chain = _as_mapping(evidence.get("auditChain"))
    if audit_chain.get("verified") is not True:
        errors.append("evidence.auditChain.verified must be true")
    runtime_events = _as_mapping(evidence.get("runtimeEvents"))
    if runtime_events.get("contentFree") is not True:
        errors.append("evidence.runtimeEvents.contentFree must be true")

    return TrustBundleVerification(valid=not errors, errors=errors, computed_hash=computed_hash)


def trust_bundle_hash(bundle: Mapping[str, Any]) -> str:
    """Return ``sha256:<hex>`` for a bundle with ``bundleHash`` excluded."""

    canonical = _canonical_json(_without_bundle_hash(bundle))
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return f"sha256:{digest}"


def _audit_chain_summary(records: Sequence[AuditRecord], verified: bool) -> dict[str, Any]:
    hashes = [record.content_hash() for record in records]
    return {
        "recordCount": len(records),
        "verified": bool(verified),
        "headHash": _prefixed_hash(hashes[0]) if hashes else "",
        "tailHash": _prefixed_hash(hashes[-1]) if hashes else "",
    }


def _prefixed_hash(value: str) -> str:
    return value if value.startswith("sha256:") or not value else f"sha256:{value}"


def _canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _without_bundle_hash(bundle: Mapping[str, Any]) -> dict[str, Any]:
    data = dict(bundle)
    data.pop("bundleHash", None)
    return data


def _contains_content_keys(value: Any) -> bool:
    if isinstance(value, Mapping):
        for key, nested in value.items():
            normalized = str(key).replace("_", "").replace("-", "").lower()
            if normalized in CONTENT_KEY_NAMES:
                return True
            if _contains_content_keys(nested):
                return True
    elif isinstance(value, list | tuple):
        return any(_contains_content_keys(item) for item in value)
    return False


def _as_mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _installed_version() -> str:
    try:
        return version("gavio")
    except PackageNotFoundError:
        return "2.0.0"
