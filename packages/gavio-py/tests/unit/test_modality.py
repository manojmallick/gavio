"""Tests for image PII detection — ModalityGuard (F-SEC-09)."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio import Gateway
from gavio.exceptions import PiiBlockedError
from gavio.interceptors.audit import AuditRecord
from gavio.interceptors.audit.interceptor import AuditInterceptor
from gavio.interceptors.audit.sink import AuditSink
from gavio.interceptors.pii import ModalityGuard, ModalityScanner, ModalityScanResult
from gavio.providers.mock import MockProvider

# repo_root/packages/gavio-py/tests/unit/test_modality.py -> repo_root/test-vectors
_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"
_IMG = b"\x01\x02\x03"


class _CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


class _StubScanner(ModalityScanner):
    def __init__(self, text: str = "", entity_types: list[str] | None = None) -> None:
        self._text = text
        self._types = entity_types or []

    @property
    def name(self) -> str:
        return "stub"

    def scan(self, image: bytes) -> ModalityScanResult:
        return ModalityScanResult(text=self._text, entity_types=list(self._types))


def _gw(scanner: ModalityScanner, sink: AuditSink, on_detect: str = "tag") -> Gateway:
    return (
        Gateway.builder()
        .adapter(MockProvider(response="ok"))
        .model("mock")
        .use(AuditInterceptor(sink))
        .use(ModalityGuard([scanner], on_detect=on_detect))
        .build()
    )


async def _detect(scanner: ModalityScanner) -> list[str]:
    sink = _CollectingSink()
    await _gw(scanner, sink).complete(messages=[{"role": "user", "content": "q"}], images=[_IMG])
    return sorted(sink.records[0].pii_entity_types)


async def test_records_ocr_text_pii() -> None:
    assert await _detect(_StubScanner("contact jan.devries@example.com")) == ["EMAIL"]


async def test_records_direct_face_detection() -> None:
    assert await _detect(_StubScanner("", ["FACE"])) == ["FACE"]


async def test_unions_text_and_direct() -> None:
    assert await _detect(_StubScanner("mail a@b.com", ["FACE"])) == ["EMAIL", "FACE"]


async def test_clean_image_records_nothing() -> None:
    assert await _detect(_StubScanner("a sunset over the mountains")) == []


async def test_noop_without_images() -> None:
    sink = _CollectingSink()
    await _gw(_StubScanner("", ["FACE"]), sink).complete(
        messages=[{"role": "user", "content": "q"}]
    )
    assert sink.records[0].pii_entity_types == []


async def test_block_raises_pii_blocked_error() -> None:
    sink = _CollectingSink()
    gw = _gw(_StubScanner("", ["FACE"]), sink, on_detect="block")
    with pytest.raises(PiiBlockedError):
        await gw.complete(messages=[{"role": "user", "content": "q"}], images=[_IMG])


def _load_image_vectors() -> list[dict]:
    return json.loads((_VECTORS / "pii" / "image-detection.json").read_text())["cases"]


@pytest.mark.parametrize("case", _load_image_vectors(), ids=lambda c: c["id"])
async def test_image_detection_vectors(case: dict) -> None:
    detected = await _detect(_StubScanner(case["ocrText"], case["entityTypes"]))
    assert detected == case["expectedTypes"], (
        f"{case['id']}: expected {case['expectedTypes']}, got {detected}"
    )
