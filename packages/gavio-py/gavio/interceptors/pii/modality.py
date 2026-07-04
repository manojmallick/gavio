"""ModalityScanner + ModalityGuard (F-SEC-09) — image PII detection.

Extends the PII pipeline to image inputs (``request.images``). Each
ModalityScanner extracts text (OCR) and/or direct detections (e.g. faces);
extracted text is run through the standard tier-1 PII text scanners. Detected
entity types are recorded on the context, so they land in the AuditRecord's
``pii_entity_types``. Images are scanned in the ``before`` hook — before any
provider call.
"""

from __future__ import annotations

import io
from abc import ABC, abstractmethod
from dataclasses import dataclass, field

from ...context import InterceptorContext
from ...exceptions import PiiBlockedError
from ...request import GavioRequest
from ..base import Interceptor
from .context import ScanContext
from .scanner import PiiScanner
from .scanners import default_scanners


@dataclass
class ModalityScanResult:
    #: OCR-extracted text (empty when none). Fed to the text PII scanners.
    text: str = ""
    #: Direct entity detections, e.g. ``["FACE"]`` from face detection.
    entity_types: list[str] = field(default_factory=list)


class ModalityScanner(ABC):
    """Detects PII in a non-text modality (images today; audio/video later)."""

    @property
    @abstractmethod
    def name(self) -> str: ...

    @abstractmethod
    def scan(self, image: bytes) -> ModalityScanResult: ...


class ModalityGuard(Interceptor):
    """Scan ``request.images`` for PII before the provider call (F-SEC-09)."""

    def __init__(
        self,
        scanners: list[ModalityScanner],
        text_scanners: list[PiiScanner] | None = None,
        on_detect: str = "tag",
    ) -> None:
        if on_detect not in ("tag", "block"):
            raise ValueError("on_detect must be 'tag' or 'block'")
        self.scanners = scanners
        self.text_scanners = text_scanners if text_scanners is not None else default_scanners()
        self.on_detect = on_detect

    @property
    def name(self) -> str:
        return "modality_guard"

    async def before(self, request: GavioRequest, ctx: InterceptorContext) -> GavioRequest:
        if not request.images:
            return request
        found: set[str] = set()
        for image in request.images:
            for scanner in self.scanners:
                result = scanner.scan(image)
                found.update(result.entity_types)
                if result.text:
                    scan_ctx = ScanContext()
                    for ts in self.text_scanners:
                        if ts.scan(result.text, scan_ctx):
                            found.add(ts.entity_type)
        if found:
            ctx.record_pii(sorted(found))
            if self.on_detect == "block":
                raise PiiBlockedError(sorted(found))
        return request


class OcrModalityScanner(ModalityScanner):
    """Reference OCR ModalityScanner backed by the optional ``ocr`` extra.

    Extracts text from an image for the text PII scanners; performs no face
    detection. Raises a clear error if the optional dependency is not installed.
    """

    def __init__(self, lang: str = "eng") -> None:
        self.lang = lang

    @property
    def name(self) -> str:
        return "ocr"

    def scan(self, image: bytes) -> ModalityScanResult:
        try:
            import pytesseract  # type: ignore[import-not-found]
            from PIL import Image  # type: ignore[import-not-found]
        except ImportError as exc:  # pragma: no cover - exercised only without the extra
            raise RuntimeError(
                "OcrModalityScanner requires the optional 'ocr' extra "
                "(pip install 'gavio[ocr]') for pytesseract + pillow"
            ) from exc
        text = pytesseract.image_to_string(Image.open(io.BytesIO(image)), lang=self.lang)
        return ModalityScanResult(text=text, entity_types=[])
