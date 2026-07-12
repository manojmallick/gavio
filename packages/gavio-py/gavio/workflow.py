"""Platform workflow release artifacts.

The workflow release joins prompts, evals, policy packs, production trust
evidence, and platform runtime profiles into one metadata-only release record.
It is deliberately local and deterministic so the CLI can produce review
artifacts without needing a running provider or control plane.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .interceptors.pii import PolicyPack, load_policy_pack
from .platform_runtime import verify_platform_runtime_profile
from .prompts import (
    EvalRunResult,
    build_prompt_release_bundle,
    load_eval_document,
    run_eval_file,
)
from .trust import verify_production_trust_bundle

WORKFLOW_MANIFEST_SCHEMA_VERSION = "gavio.platform-workflow.v1"
WORKFLOW_RELEASE_SCHEMA_VERSION = "gavio.platform-workflow-release.v1"

_CONTENT_KEY_NAMES = {
    "messages",
    "content",
    "diff",
    "raw",
    "rawmessages",
    "rawprompt",
    "rawresponse",
    "prompt",
    "prompttext",
    "completion",
    "response",
    "responsetext",
    "input",
    "inputtext",
    "output",
    "outputtext",
    "rawinput",
    "rawoutput",
    "renderedprompt",
    "text",
}


@dataclass(frozen=True)
class PlatformWorkflowReleaseResult:
    """Built workflow release artifact plus gate status."""

    artifact: dict[str, Any]
    passed: bool
    reasons: tuple[str, ...]


def run_platform_workflow_release_file(
    manifest_path: str | Path,
) -> PlatformWorkflowReleaseResult:
    """Load a workflow manifest and build its release artifact."""

    path = Path(manifest_path).expanduser()
    manifest = load_eval_document(path)
    return build_platform_workflow_release(manifest, base_path=path.parent)


def build_platform_workflow_release(
    manifest: Mapping[str, Any],
    *,
    base_path: str | Path | None = None,
) -> PlatformWorkflowReleaseResult:
    """Build a deterministic metadata-only platform workflow release."""

    base = Path(base_path or ".").expanduser()
    workflow_id = str(manifest.get("workflowId", manifest.get("workflow_id", "workflow-release")))
    generated_at = str(
        manifest.get("generatedAt")
        or manifest.get("generated_at")
        or datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    )

    prompt_section = _as_mapping(manifest.get("prompts", manifest.get("prompt")))
    prompt_manifest_path = _optional_path(base, prompt_section.get("manifest"))
    prompt_manifest = (
        load_eval_document(prompt_manifest_path) if prompt_manifest_path is not None else None
    )

    eval_results = _run_eval_sections(
        manifest,
        base_path=base,
        prompt_manifest_path=prompt_manifest_path,
    )
    prompt_bundles = _build_prompt_bundles(
        prompt_section,
        prompt_manifest,
        reports=[result.report for result in eval_results],
        generated_at=generated_at,
    )
    policy_evidence = _policy_evidence(manifest, base)
    trust_evidence = _trust_evidence(manifest, base)
    profile_evidence = _profile_evidence(manifest, base)

    reasons: list[str] = []
    for result in eval_results:
        if not result.passed:
            reasons.extend(f"eval:{reason}" for reason in result.gate.reasons)
            if result.workflow is not None and not result.workflow.passed:
                for gate in result.workflow.gates:
                    reasons.extend(f"prompt:{reason}" for reason in gate.reasons)
    for bundle in prompt_bundles:
        if not bundle.get("passed", False):
            reasons.append(
                "prompt release bundle "
                f"{bundle.get('bundleId', bundle.get('prompt', {}))} did not pass"
            )
    for policy in policy_evidence:
        if not policy["signatureValid"]:
            reasons.append(f"policy {policy['id']} signature is invalid")
    if trust_evidence is not None and not trust_evidence["valid"]:
        reasons.extend(f"trust:{error}" for error in trust_evidence["errors"])
    if profile_evidence is not None:
        if not profile_evidence["valid"]:
            reasons.extend(f"profile:{error}" for error in profile_evidence["errors"])
        if not profile_evidence["readiness"].get("ready"):
            reasons.extend(
                f"profile:{gap['code']}"
                for gap in profile_evidence["readiness"].get("gaps", [])
                if isinstance(gap, dict) and "code" in gap
            )

    artifact: dict[str, Any] = {
        "schemaVersion": WORKFLOW_RELEASE_SCHEMA_VERSION,
        "workflowId": workflow_id,
        "generatedAt": generated_at,
        "release": _sanitize_metadata(dict(_as_mapping(manifest.get("release")))),
        "passed": not reasons,
        "reasons": reasons,
        "prompts": {
            "manifest": _prompt_manifest_identity(prompt_manifest) if prompt_manifest else None,
            "releaseBundles": prompt_bundles,
        },
        "evals": [result.to_dict() for result in eval_results],
        "policies": policy_evidence,
        "trust": trust_evidence,
        "runtimeProfile": profile_evidence,
        "metadata": _sanitize_metadata(dict(_as_mapping(manifest.get("metadata")))),
    }
    artifact["workflowHash"] = platform_workflow_release_hash(artifact)
    return PlatformWorkflowReleaseResult(
        artifact=artifact,
        passed=not reasons,
        reasons=tuple(reasons),
    )


def platform_workflow_release_hash(artifact: Mapping[str, Any]) -> str:
    """Return ``sha256:<hex>`` for a workflow release without its hash field."""

    data = dict(artifact)
    data.pop("workflowHash", None)
    canonical = json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return f"sha256:{hashlib.sha256(canonical.encode('utf-8')).hexdigest()}"


def _run_eval_sections(
    manifest: Mapping[str, Any],
    *,
    base_path: Path,
    prompt_manifest_path: Path | None,
) -> list[EvalRunResult]:
    results: list[EvalRunResult] = []
    for raw_eval in _as_list(manifest.get("evals", manifest.get("eval"))):
        config = {"suite": raw_eval} if isinstance(raw_eval, str) else dict(_as_mapping(raw_eval))
        suite_path = _required_path(base_path, config.get("suite"), "eval suite")
        templates = list(_string_list(config.get("templates", config.get("templateFiles"))))
        template_path = config.get("template", config.get("templatesFile"))
        if template_path is not None:
            templates.append(str(template_path))
        if prompt_manifest_path is not None and not templates:
            templates.append(str(prompt_manifest_path))
        baseline = config.get("baseline")
        results.append(
            run_eval_file(
                suite_path,
                template_paths=[_resolve(base_path, value) for value in templates],
                fail_under=_optional_float(config.get("failUnder", config.get("fail_under"))),
                baseline_path=_resolve(base_path, baseline) if baseline is not None else None,
                max_regression=float(
                    config.get("maxRegression", config.get("max_regression", 0.0))
                ),
            )
        )
    return results


def _build_prompt_bundles(
    prompt_section: Mapping[str, Any],
    prompt_manifest: dict[str, Any] | None,
    *,
    reports: Sequence[Any],
    generated_at: str,
) -> list[dict[str, Any]]:
    if prompt_manifest is None:
        return []
    raw_releases = prompt_section.get("releases", prompt_section.get("release"))
    releases = _as_list(raw_releases)
    if not releases and prompt_section.get("promptId", prompt_section.get("prompt_id")):
        releases = [prompt_section]
    bundles: list[dict[str, Any]] = []
    for raw in releases:
        config = dict(_as_mapping(raw))
        prompt_id = str(config.get("promptId", config.get("prompt_id")))
        prompt_version = str(config.get("promptVersion", config.get("prompt_version")))
        bundle = build_prompt_release_bundle(
            manifest=prompt_manifest,
            prompt_id=prompt_id,
            prompt_version=prompt_version,
            from_version=config.get("fromVersion", config.get("from_version")),
            reports=reports,
            generated_at=str(config.get("generatedAt", generated_at)),
            bundle_id=config.get("bundleId", config.get("bundle_id")),
            metadata=dict(_as_mapping(config.get("metadata"))),
        )
        bundles.append(bundle.to_dict())
    return bundles


def _policy_evidence(manifest: Mapping[str, Any], base: Path) -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    for raw_policy in _as_list(manifest.get("policies", manifest.get("policyPacks"))):
        config = (
            {"pathOrName": raw_policy}
            if isinstance(raw_policy, str)
            else dict(_as_mapping(raw_policy))
        )
        path_or_name = str(
            config.get("pathOrName")
            or config.get("path_or_name")
            or config.get("path")
            or config.get("name")
            or config.get("id")
        )
        pack = _load_policy(path_or_name, base)
        manifest_data = pack.manifest()
        evidence.append(
            {
                "id": str(config.get("id") or pack.id),
                "name": pack.name,
                "version": pack.version,
                "domain": pack.domain,
                "source": path_or_name,
                "signatureValid": pack.verify_signature(),
                "manifestDigest": _sha256_json(manifest_data),
                "detectorCount": len(pack.detectors),
                "metadata": _sanitize_metadata(dict(_as_mapping(config.get("metadata")))),
            }
        )
    return evidence


def _trust_evidence(manifest: Mapping[str, Any], base: Path) -> dict[str, Any] | None:
    raw = manifest.get("trustBundle", manifest.get("trust"))
    if raw is None:
        return None
    bundle = _load_section_payload(raw, base)
    verification = verify_production_trust_bundle(bundle)
    return {
        "valid": verification.valid,
        "errors": list(verification.errors),
        "computedHash": verification.computed_hash,
        "bundle": _sanitize_metadata(dict(bundle)),
    }


def _profile_evidence(manifest: Mapping[str, Any], base: Path) -> dict[str, Any] | None:
    raw = manifest.get("runtimeProfile", manifest.get("platformRuntimeProfile"))
    if raw is None:
        return None
    profile = _load_section_payload(raw, base)
    verification = verify_platform_runtime_profile(profile)
    return {
        "valid": verification.valid,
        "errors": list(verification.errors),
        "computedHash": verification.computed_hash,
        "readiness": dict(verification.readiness),
        "profile": _sanitize_metadata(dict(profile)),
    }


def _load_section_payload(raw: Any, base: Path) -> dict[str, Any]:
    if isinstance(raw, str):
        return load_eval_document(_resolve(base, raw))
    config = _as_mapping(raw)
    path = config.get("path")
    if path is not None:
        return load_eval_document(_resolve(base, path))
    data = config.get("data", config.get("bundle", config.get("profile")))
    if isinstance(data, Mapping):
        return dict(data)
    return dict(config)


def _prompt_manifest_identity(manifest: Mapping[str, Any]) -> dict[str, Any]:
    signature = manifest.get("signature")
    identity: dict[str, Any] = {
        "schemaVersion": manifest.get("schemaVersion"),
        "registryId": manifest.get("registryId"),
        "digest": _sha256_json(
            {key: value for key, value in manifest.items() if key != "signature"}
        ),
    }
    if isinstance(signature, Mapping):
        identity["signature"] = {
            key: signature[key]
            for key in ("algorithm", "keyId", "value")
            if key in signature
        }
    return identity


def _load_policy(path_or_name: str, base: Path | None = None) -> PolicyPack:
    path = Path(path_or_name).expanduser()
    if path.is_absolute():
        candidates = [path]
    else:
        candidates = [base / path, path] if base is not None else [path]
    for candidate in candidates:
        if candidate.exists():
            return PolicyPack.load_path(candidate)
    return load_policy_pack(path_or_name)


def _sanitize_metadata(value: Any) -> Any:
    if isinstance(value, Mapping):
        out: dict[str, Any] = {}
        for key, item in value.items():
            normalized = _normalize_key(key)
            if normalized in _CONTENT_KEY_NAMES:
                out[f"{key}Hash"] = _sha256_json(item)
                continue
            out[str(key)] = _sanitize_metadata(item)
        return out
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [_sanitize_metadata(item) for item in value]
    return value


def _sha256_json(value: Any) -> str:
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _normalize_key(value: Any) -> str:
    return str(value).replace("_", "").replace("-", "").lower()


def _as_mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def _string_list(value: Any) -> list[str]:
    if value is None:
        return []
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [str(item) for item in value]
    return [str(value)]


def _optional_float(value: Any) -> float | None:
    return None if value is None else float(value)


def _resolve(base: Path, value: str | Path) -> Path:
    path = Path(value).expanduser()
    return path if path.is_absolute() else base / path


def _optional_path(base: Path, value: Any) -> Path | None:
    return None if value is None else _resolve(base, str(value))


def _required_path(base: Path, value: Any, label: str) -> Path:
    if value is None:
        raise ValueError(f"platform workflow release requires {label}")
    return _resolve(base, str(value))
