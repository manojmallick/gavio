"""PiiMatch — a single detected PII entity within a span of text."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PiiMatch:
    """One detected entity.

    ``start``/``end`` are half-open character offsets into the scanned text.
    ``replacement`` is the placeholder used in REDACT mode; ``value`` is the
    original text (never logged — used only for restore).
    """

    entity_type: str
    start: int
    end: int
    value: str
    confidence: float = 1.0
    replacement: str | None = None

    def __post_init__(self) -> None:
        if self.start < 0 or self.end < self.start:
            raise ValueError(
                f"Invalid PiiMatch span: start={self.start}, end={self.end}"
            )

    @property
    def length(self) -> int:
        return self.end - self.start
