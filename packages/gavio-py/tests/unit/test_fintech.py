"""Tests for the FinTech policy pack — SWIFT/BIC + routing number (F-SEC-01)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio.context import InterceptorContext
from gavio.interceptors.pii import PiiGuard, fintech_scanners
from gavio.interceptors.pii.context import ScanContext
from gavio.interceptors.pii.scanners import RoutingNumberScanner, SwiftBicScanner
from gavio.interceptors.pii.scanners.routing_number import valid_routing_number
from gavio.request import GavioRequest
from gavio.types import Provider

# repo_root/packages/gavio-py/tests/unit/test_fintech.py -> repo_root/test-vectors
_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


async def _detect(text: str) -> list[str]:
    guard = PiiGuard(scanners=fintech_scanners())
    ctx = InterceptorContext(trace_id="t")
    req = GavioRequest(
        messages=[{"role": "user", "content": text}],
        model="mock",
        provider=Provider.MOCK,
    )
    await guard.before(req, ctx)
    return sorted(set(ctx.pii_entity_types))


def test_routing_checksum() -> None:
    assert valid_routing_number("021000021")
    assert valid_routing_number("111000025")
    assert not valid_routing_number("123456789")
    assert not valid_routing_number("000000000")
    assert not valid_routing_number("12345")


def test_swift_context_gated() -> None:
    matches = SwiftBicScanner().scan("SWIFT: DEUTDEFF500 now", ScanContext())
    assert len(matches) == 1
    assert matches[0].value == "DEUTDEFF500"
    assert SwiftBicScanner().scan("the DATABASE was updated", ScanContext()) == []


def test_routing_scanner() -> None:
    assert len(RoutingNumberScanner().scan("021000021", ScanContext())) == 1
    assert RoutingNumberScanner().scan("123456789", ScanContext()) == []


async def test_composition() -> None:
    assert await _detect("SWIFT DEUTDEFF500 and routing 111000025") == [
        "ROUTING_NUMBER",
        "SWIFT_BIC",
    ]


def _load_fintech_vectors() -> list[dict]:
    return json.loads((_VECTORS / "pii" / "fintech-detection.json").read_text())["cases"]


@pytest.mark.parametrize("case", _load_fintech_vectors(), ids=lambda c: c["id"])
async def test_fintech_vectors(case: dict) -> None:
    detected = await _detect(case["text"])
    assert detected == case["expectedTypes"], (
        f"{case['id']}: expected {case['expectedTypes']}, got {detected}"
    )
