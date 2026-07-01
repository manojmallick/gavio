"""Runs the shared cross-SDK test vectors from //test-vectors against the Python SDK.

These are the same JSON files the Java and JavaScript SDKs run, so a parity
regression in any one language shows up here.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio.context import InterceptorContext
from gavio.interceptors.pii import PiiGuard
from gavio.interceptors.pii.context import ScanContext
from gavio.interceptors.pii.scanners import (
    BsnScanner,
    CreditCardScanner,
    EmailScanner,
    IbanScanner,
    IpAddressScanner,
    PhoneScanner,
    SecretScanner,
    SsnScanner,
)
from gavio.request import GavioRequest
from gavio.types import Provider

# repo_root/packages/gavio-py/tests/unit/test_vectors.py -> repo_root/test-vectors
_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"

_SCANNERS = {
    "EMAIL": EmailScanner,
    "IBAN": IbanScanner,
    "BSN": BsnScanner,
    "CREDIT_CARD": CreditCardScanner,
    "PHONE": PhoneScanner,
    "IP_ADDRESS": IpAddressScanner,
    "SSN": SsnScanner,
    "SECRET": SecretScanner,
}


def _load(name: str) -> list[dict]:
    return json.loads((_VECTORS / "pii" / name).read_text())["cases"]


@pytest.mark.parametrize("case", _load("checksums.json"), ids=lambda c: c["id"])
def test_checksum_vectors(case: dict) -> None:
    scanner = _SCANNERS[case["scanner"]]()
    matches = scanner.scan(case["text"], ScanContext())
    assert bool(matches) == case["shouldMatch"], (
        f"{case['id']}: {case['scanner']} on {case['text']!r} "
        f"expected shouldMatch={case['shouldMatch']}, got {len(matches)} matches"
    )


@pytest.mark.parametrize("case", _load("detection.json"), ids=lambda c: c["id"])
async def test_detection_vectors(case: dict) -> None:
    guard = PiiGuard()
    ctx = InterceptorContext(trace_id="t")
    req = GavioRequest(
        messages=[{"role": "user", "content": case["text"]}],
        model="mock",
        provider=Provider.MOCK,
    )
    await guard.before(req, ctx)
    detected = sorted(set(ctx.pii_entity_types))
    assert detected == case["expectedTypes"], (
        f"{case['id']}: expected {case['expectedTypes']}, got {detected}"
    )
