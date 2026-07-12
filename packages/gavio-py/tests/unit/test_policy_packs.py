"""Policy Pack architecture tests (F-PACK-01/02/05)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio.context import InterceptorContext
from gavio.interceptors.pii import (
    PiiGuard,
    RegexPolicyRule,
    core_policy_pack,
    custom_policy_pack,
    fintech_policy_pack,
    fintech_scanners,
    policy_pack_scanners,
)
from gavio.request import GavioRequest
from gavio.types import Provider

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


def _load_vectors() -> dict:
    return json.loads((_VECTORS / "policy-packs" / "manifest.json").read_text())


def _detector_entity_types(manifest: dict) -> list[str]:
    return [detector["entityType"] for detector in manifest["detectors"]]


async def _detect(text: str, scanners: list) -> list[str]:
    guard = PiiGuard(scanners=scanners, log_entity_types=False)
    ctx = InterceptorContext(trace_id="t")
    req = GavioRequest(
        messages=[{"role": "user", "content": text}],
        model="mock",
        provider=Provider.MOCK,
    )
    await guard.before(req, ctx)
    return sorted(set(ctx.pii_entity_types))


def test_builtin_policy_pack_manifests() -> None:
    vectors = _load_vectors()
    packs = {
        "gavio.core-pii": core_policy_pack(),
        "gavio.fintech": fintech_policy_pack(),
    }
    for expected in vectors["builtinPacks"]:
        manifest = packs[expected["id"]].manifest()
        assert manifest["id"] == expected["id"]
        assert manifest["name"] == expected["name"]
        assert manifest["version"] == expected["version"]
        assert manifest["domain"] == expected["domain"]
        assert manifest["defaultAction"] == expected["defaultAction"]
        assert manifest["redactionStrategy"] == expected["redactionStrategy"]
        assert manifest["auditLabels"] == expected["auditLabels"]
        assert _detector_entity_types(manifest) == expected["detectorEntityTypes"]


def test_fintech_scanners_are_backed_by_policy_pack() -> None:
    assert [scanner.entity_type for scanner in fintech_scanners()] == [
        detector.entity_type for detector in fintech_policy_pack().detectors
    ]


@pytest.mark.parametrize("case", _load_vectors()["customRulePack"]["cases"], ids=lambda c: c["id"])
async def test_custom_regex_rule_policy_pack(case: dict) -> None:
    vector = _load_vectors()["customRulePack"]
    rules = [
        RegexPolicyRule(
            name=rule["name"],
            entity_type=rule["entityType"],
            pattern=rule["pattern"],
            confidence=rule["confidence"],
            replacement_prefix=rule["replacementPrefix"],
            action=rule["action"],
            redaction_strategy=rule["redactionStrategy"],
            label=rule["label"],
        )
        for rule in vector["rules"]
    ]
    pack = custom_policy_pack(
        id=vector["id"],
        name=vector["name"],
        version=vector["version"],
        domain=vector["domain"],
        rules=rules,
        default_action=vector["defaultAction"],
        redaction_strategy=vector["redactionStrategy"],
        audit_labels=vector["auditLabels"],
    )
    manifest = pack.manifest()
    assert manifest["id"] == vector["id"]
    assert manifest["defaultAction"] == vector["defaultAction"]
    assert manifest["redactionStrategy"] == vector["redactionStrategy"]
    assert manifest["auditLabels"] == vector["auditLabels"]
    assert manifest["detectors"][0]["pattern"] == vector["rules"][0]["pattern"]
    assert await _detect(case["text"], policy_pack_scanners(pack)) == case["expectedTypes"]
