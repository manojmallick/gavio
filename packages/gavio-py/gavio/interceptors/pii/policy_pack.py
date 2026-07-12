"""Policy Pack framework (F-PACK-01/02/05).

Policy packs group scanners with a manifest that can be shown in docs, audit
metadata, or UI surfaces. The scanners still plug into ``PiiGuard`` unchanged.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

from .context import ScanContext
from .match import PiiMatch
from .scanner import PiiScanner
from .scanners.bsn import BsnScanner
from .scanners.credit_card import CreditCardScanner
from .scanners.email import EmailScanner
from .scanners.iban import IbanScanner
from .scanners.ip_address import IpAddressScanner
from .scanners.phone import PhoneScanner
from .scanners.routing_number import RoutingNumberScanner
from .scanners.secret import SecretScanner
from .scanners.ssn import SsnScanner
from .scanners.swift_bic import SwiftBicScanner


def _wire(value: Enum | str) -> str:
    return value.value if isinstance(value, Enum) else str(value)


class PolicyAction(str, Enum):
    ALLOW = "allow"
    FLAG = "flag"
    REDACT = "redact"
    MASK = "mask"
    HASH = "hash"
    BLOCK = "block"
    ROUTE = "route"
    REQUIRE_APPROVAL = "require-approval"


class RedactionStrategy(str, Enum):
    TOKENIZE = "tokenize"
    MASK = "mask"
    HASH = "hash"
    REDACT = "redact"


@dataclass(frozen=True)
class PolicyPackSignature:
    algorithm: str = "sha256"
    value: str | None = None
    key_id: str | None = None
    signed_at: str | None = None

    def manifest(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "algorithm": self.algorithm,
            "value": self.value,
        }
        if self.key_id is not None:
            out["keyId"] = self.key_id
        if self.signed_at is not None:
            out["signedAt"] = self.signed_at
        return out


@dataclass(frozen=True)
class PolicyDetector:
    name: str
    entity_type: str
    detector_type: str = "scanner"
    action: PolicyAction | str = PolicyAction.REDACT
    label: str | None = None
    severity: str | None = None
    confidence: float = 1.0
    redaction_strategy: RedactionStrategy | str = RedactionStrategy.TOKENIZE
    pattern: str | None = None
    replacement_prefix: str | None = None
    suppression_patterns: tuple[str, ...] = field(default_factory=tuple)

    def manifest(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "name": self.name,
            "entityType": self.entity_type,
            "type": self.detector_type,
            "action": _wire(self.action),
            "confidence": self.confidence,
            "redactionStrategy": _wire(self.redaction_strategy),
        }
        if self.label is not None:
            out["label"] = self.label
        if self.severity is not None:
            out["severity"] = self.severity
        if self.pattern is not None:
            out["pattern"] = self.pattern
        if self.replacement_prefix is not None:
            out["replacementPrefix"] = self.replacement_prefix
        if self.suppression_patterns:
            out["suppressionPatterns"] = list(self.suppression_patterns)
        return out


@dataclass(frozen=True)
class RegexPolicyRule:
    name: str
    entity_type: str
    pattern: str
    confidence: float = 1.0
    replacement_prefix: str | None = None
    action: PolicyAction | str | None = None
    redaction_strategy: RedactionStrategy | str | None = None
    label: str | None = None
    severity: str | None = None
    suppression_patterns: tuple[str, ...] = field(default_factory=tuple)


class RegexRuleScanner(PiiScanner):
    """Scanner generated from a custom organization regex rule."""

    def __init__(self, rule: RegexPolicyRule) -> None:
        self._rule = rule
        self._pattern = re.compile(rule.pattern)
        self._suppressions = tuple(
            re.compile(pattern) for pattern in rule.suppression_patterns
        )

    @property
    def entity_type(self) -> str:
        return self._rule.entity_type

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for match in self._pattern.finditer(text):
            if any(pattern.search(match.group(0)) for pattern in self._suppressions):
                continue
            idx = ctx.next_index(self.entity_type)
            prefix = self._rule.replacement_prefix or self.entity_type
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=match.start(),
                    end=match.end(),
                    value=match.group(0),
                    confidence=self._rule.confidence,
                    replacement=f"[{prefix}_{idx}]",
                )
            )
        return out


@dataclass(frozen=True)
class PolicyPack:
    id: str
    name: str
    version: str
    domain: str
    description: str
    detectors: tuple[PolicyDetector, ...]
    scanners: tuple[PiiScanner, ...] = field(default_factory=tuple)
    default_action: PolicyAction | str = PolicyAction.REDACT
    redaction_strategy: RedactionStrategy | str = RedactionStrategy.TOKENIZE
    audit_labels: tuple[str, ...] = field(default_factory=tuple)
    compatibility: dict[str, str] = field(default_factory=dict)
    signature: PolicyPackSignature | None = None
    schema: str | None = None
    schema_version: str | None = None

    def manifest(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "id": self.id,
            "name": self.name,
            "version": self.version,
            "domain": self.domain,
            "description": self.description,
            "defaultAction": _wire(self.default_action),
            "redactionStrategy": _wire(self.redaction_strategy),
            "auditLabels": list(self.audit_labels),
            "detectors": [detector.manifest() for detector in self.detectors],
        }
        if self.schema is not None:
            out["$schema"] = self.schema
        if self.schema_version is not None:
            out["schemaVersion"] = self.schema_version
        if self.compatibility:
            out["compatibility"] = dict(self.compatibility)
        if self.signature is not None:
            out["signature"] = self.signature.manifest()
        return out

    def scanner_list(self) -> list[PiiScanner]:
        return list(self.scanners)

    @classmethod
    def load(cls, name: str) -> PolicyPack:
        """Load a catalog policy pack by name, e.g. ``finance``."""
        return load_policy_pack(name)

    @classmethod
    def load_path(cls, path: str | Path) -> PolicyPack:
        """Load a policy pack from a directory or manifest JSON file."""
        return load_policy_pack_path(path)

    def verify_signature(self) -> bool:
        if self.signature is None or self.signature.algorithm != "sha256":
            return False
        expected = self.signature.value
        if not expected:
            return False
        return self.signature_value() == expected

    def signature_value(self) -> str:
        return _canonical_manifest_digest(self.manifest())

    def with_overrides(self, overrides: dict[str, Any]) -> PolicyPack:
        manifest = self.manifest()
        detector_overrides: dict[str, Any] = overrides.get("detectors", {})
        if "defaultAction" in overrides:
            manifest["defaultAction"] = overrides["defaultAction"]
        if "redactionStrategy" in overrides:
            manifest["redactionStrategy"] = overrides["redactionStrategy"]
        if "auditLabels" in overrides:
            manifest["auditLabels"] = list(overrides["auditLabels"])
        for detector in manifest["detectors"]:
            override = detector_overrides.get(detector["name"])
            if override:
                detector.update(override)
        manifest.pop("signature", None)
        return _pack_from_manifest(manifest)


def _detector(
    name: str,
    entity_type: str,
    *,
    label: str | None = None,
    action: PolicyAction | str = PolicyAction.REDACT,
    redaction_strategy: RedactionStrategy | str = RedactionStrategy.TOKENIZE,
) -> PolicyDetector:
    return PolicyDetector(
        name=name,
        entity_type=entity_type,
        action=action,
        label=label,
        redaction_strategy=redaction_strategy,
    )


def core_policy_pack() -> PolicyPack:
    detectors = (
        _detector("secret", "SECRET", label="PII"),
        _detector("email", "EMAIL", label="PII"),
        _detector("iban", "IBAN", label="PII"),
        _detector("bsn", "BSN", label="PII"),
        _detector("credit_card", "CREDIT_CARD", label="PII"),
        _detector("ssn", "SSN", label="PII"),
        _detector("phone", "PHONE", label="PII"),
        _detector("ip_address", "IP_ADDRESS", label="PII"),
    )
    return PolicyPack(
        id="gavio.core-pii",
        name="Core PII",
        version="0.12.0",
        domain="core",
        description="Built-in deterministic PII scanners.",
        detectors=detectors,
        scanners=(
            SecretScanner(),
            EmailScanner(),
            IbanScanner(),
            BsnScanner(),
            CreditCardScanner(),
            SsnScanner(),
            PhoneScanner(),
            IpAddressScanner(),
        ),
        audit_labels=("PII",),
    )


def fintech_policy_pack() -> PolicyPack:
    detectors = (
        _detector("swift_bic", "SWIFT_BIC", label="FINANCIAL_IDENTIFIER"),
        _detector("routing_number", "ROUTING_NUMBER", label="FINANCIAL_IDENTIFIER"),
    )
    return PolicyPack(
        id="gavio.fintech",
        name="FinTech",
        version="0.12.0",
        domain="fintech",
        description="Financial identifiers beyond the core PII pack.",
        detectors=detectors,
        scanners=(SwiftBicScanner(), RoutingNumberScanner()),
        audit_labels=("FINANCIAL_IDENTIFIER",),
    )


def custom_policy_pack(
    *,
    id: str,
    name: str,
    rules: list[RegexPolicyRule],
    version: str = "1.0.0",
    domain: str = "custom",
    description: str = "Custom organization policy pack.",
    default_action: PolicyAction | str = PolicyAction.REDACT,
    redaction_strategy: RedactionStrategy | str = RedactionStrategy.TOKENIZE,
    audit_labels: list[str] | None = None,
) -> PolicyPack:
    detectors = tuple(
        PolicyDetector(
            name=rule.name,
            entity_type=rule.entity_type,
            detector_type="regex",
            action=rule.action or default_action,
            label=rule.label,
            severity=rule.severity,
            confidence=rule.confidence,
            redaction_strategy=rule.redaction_strategy or redaction_strategy,
            pattern=rule.pattern,
            replacement_prefix=rule.replacement_prefix,
            suppression_patterns=tuple(rule.suppression_patterns),
        )
        for rule in rules
    )
    return PolicyPack(
        id=id,
        name=name,
        version=version,
        domain=domain,
        description=description,
        detectors=detectors,
        scanners=tuple(RegexRuleScanner(rule) for rule in rules),
        default_action=default_action,
        redaction_strategy=redaction_strategy,
        audit_labels=tuple(audit_labels or ()),
    )


def policy_pack_scanners(*packs: PolicyPack) -> list[PiiScanner]:
    scanners: list[PiiScanner] = []
    for pack in packs:
        scanners.extend(pack.scanners)
    return scanners


def list_policy_packs() -> list[str]:
    root = _catalog_root()
    return sorted(
        path.parent.relative_to(root).as_posix()
        for path in root.rglob("manifest.json")
    )


def load_policy_pack(name: str) -> PolicyPack:
    path = _catalog_root() / name / "manifest.json"
    if not path.is_file():
        raise FileNotFoundError(f"unknown policy pack: {name}")
    return load_policy_pack_path(path)


def load_policy_pack_path(path: str | Path) -> PolicyPack:
    manifest_path = Path(path)
    if manifest_path.is_dir():
        manifest_path = manifest_path / "manifest.json"
    return _pack_from_manifest(json.loads(manifest_path.read_text()))


def _pack_from_manifest(manifest: dict[str, Any]) -> PolicyPack:
    default_action = manifest.get("defaultAction", PolicyAction.REDACT.value)
    redaction_strategy = manifest.get(
        "redactionStrategy", RedactionStrategy.TOKENIZE.value
    )
    detectors: list[PolicyDetector] = []
    scanners: list[PiiScanner] = []
    for item in manifest.get("detectors", []):
        detector = _detector_from_manifest(item, default_action, redaction_strategy)
        detectors.append(detector)
        scanners.extend(_scanners_from_detector(detector))
    signature = _signature_from_manifest(manifest.get("signature"))
    return PolicyPack(
        id=manifest["id"],
        name=manifest["name"],
        version=manifest["version"],
        domain=manifest["domain"],
        description=manifest.get("description", ""),
        detectors=tuple(detectors),
        scanners=tuple(scanners),
        default_action=default_action,
        redaction_strategy=redaction_strategy,
        audit_labels=tuple(manifest.get("auditLabels", ())),
        compatibility=dict(manifest.get("compatibility", {})),
        signature=signature,
        schema=manifest.get("$schema"),
        schema_version=manifest.get("schemaVersion"),
    )


def _detector_from_manifest(
    item: dict[str, Any],
    default_action: PolicyAction | str,
    default_strategy: RedactionStrategy | str,
) -> PolicyDetector:
    return PolicyDetector(
        name=item["name"],
        entity_type=item["entityType"],
        detector_type=item.get("type", "scanner"),
        action=item.get("action", default_action),
        label=item.get("label"),
        severity=item.get("severity"),
        confidence=float(item.get("confidence", 1.0)),
        redaction_strategy=item.get("redactionStrategy", default_strategy),
        pattern=item.get("pattern"),
        replacement_prefix=item.get("replacementPrefix"),
        suppression_patterns=tuple(item.get("suppressionPatterns", ())),
    )


def _signature_from_manifest(value: Any) -> PolicyPackSignature | None:
    if not isinstance(value, dict):
        return None
    return PolicyPackSignature(
        algorithm=value.get("algorithm", "sha256"),
        value=value.get("value"),
        key_id=value.get("keyId"),
        signed_at=value.get("signedAt"),
    )


def _scanners_from_detector(detector: PolicyDetector) -> list[PiiScanner]:
    if detector.detector_type == "regex":
        if detector.pattern is None:
            raise ValueError(f"regex policy detector {detector.name} is missing pattern")
        rule = RegexPolicyRule(
            name=detector.name,
            entity_type=detector.entity_type,
            pattern=detector.pattern,
            confidence=detector.confidence,
            replacement_prefix=detector.replacement_prefix,
            action=detector.action,
            redaction_strategy=detector.redaction_strategy,
            label=detector.label,
            severity=detector.severity,
            suppression_patterns=detector.suppression_patterns,
        )
        return [RegexRuleScanner(rule)]
    scanner = _BUILTIN_SCANNERS.get(detector.name) or _BUILTIN_SCANNERS.get(
        detector.entity_type
    )
    if scanner is None:
        raise ValueError(f"unknown policy-pack scanner detector: {detector.name}")
    return [scanner()]


def _canonical_manifest_digest(manifest: dict[str, Any]) -> str:
    payload = json.loads(json.dumps(manifest))
    if isinstance(payload.get("signature"), dict):
        payload["signature"]["value"] = None
    encoded = json.dumps(
        _normalize_canonical_json(payload), sort_keys=True, separators=(",", ":")
    ).encode()
    return hashlib.sha256(encoded).hexdigest()


def _normalize_canonical_json(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _normalize_canonical_json(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_normalize_canonical_json(item) for item in value]
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return value


def _catalog_root() -> Path:
    for base in (Path.cwd(), *Path(__file__).resolve().parents):
        candidate = base / "policy-packs"
        if candidate.is_dir():
            return candidate
    raise FileNotFoundError("could not locate policy-packs catalog")


_BUILTIN_SCANNERS = {
    "secret": SecretScanner,
    "SECRET": SecretScanner,
    "email": EmailScanner,
    "EMAIL": EmailScanner,
    "iban": IbanScanner,
    "IBAN": IbanScanner,
    "bsn": BsnScanner,
    "BSN": BsnScanner,
    "credit_card": CreditCardScanner,
    "CREDIT_CARD": CreditCardScanner,
    "ssn": SsnScanner,
    "SSN": SsnScanner,
    "phone": PhoneScanner,
    "PHONE": PhoneScanner,
    "ip_address": IpAddressScanner,
    "IP_ADDRESS": IpAddressScanner,
    "swift_bic": SwiftBicScanner,
    "SWIFT_BIC": SwiftBicScanner,
    "routing_number": RoutingNumberScanner,
    "ROUTING_NUMBER": RoutingNumberScanner,
}
