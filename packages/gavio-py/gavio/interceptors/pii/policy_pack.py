"""Policy Pack framework (F-PACK-01/02/05).

Policy packs group scanners with a manifest that can be shown in docs, audit
metadata, or UI surfaces. The scanners still plug into ``PiiGuard`` unchanged.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
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
class PolicyDetector:
    name: str
    entity_type: str
    detector_type: str = "scanner"
    action: PolicyAction | str = PolicyAction.REDACT
    label: str | None = None
    confidence: float = 1.0
    redaction_strategy: RedactionStrategy | str = RedactionStrategy.TOKENIZE
    pattern: str | None = None

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
        if self.pattern is not None:
            out["pattern"] = self.pattern
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


class RegexRuleScanner(PiiScanner):
    """Scanner generated from a custom organization regex rule."""

    def __init__(self, rule: RegexPolicyRule) -> None:
        self._rule = rule
        self._pattern = re.compile(rule.pattern)

    @property
    def entity_type(self) -> str:
        return self._rule.entity_type

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for match in self._pattern.finditer(text):
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

    def manifest(self) -> dict[str, Any]:
        return {
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

    def scanner_list(self) -> list[PiiScanner]:
        return list(self.scanners)


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
            confidence=rule.confidence,
            redaction_strategy=rule.redaction_strategy or redaction_strategy,
            pattern=rule.pattern,
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
