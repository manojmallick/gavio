"""Phone number scanner — E.164 and common national formats."""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

# E.164 (+CC...) or national groupings with separators. Requires at least 7
# digits total to avoid matching short numbers / years.
_PHONE = re.compile(
    r"(?<![\w.])(?:\+?\d{1,3}[ .\-]?)?(?:\(\d{1,4}\)[ .\-]?)?"
    r"\d{2,4}(?:[ .\-]?\d{2,4}){2,4}(?![\w])"
)


class PhoneScanner(PiiScanner):
    entity_type = "PHONE"

    def __init__(self, locales: list[str] | None = None) -> None:
        self.locales = locales or ["NL", "DE", "GB", "US"]

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _PHONE.finditer(text):
            digit_count = sum(c.isdigit() for c in m.group())
            if not 7 <= digit_count <= 15:
                continue
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    confidence=0.85,
                    replacement=f"[PHONE_{idx}]",
                )
            )
        return out

    def supports_locale(self, locale: str) -> bool:
        return locale.upper() in self.locales
