"""Credit card scanner — regex candidate + Luhn checksum validation."""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

# 13–19 digits, optionally separated by single spaces or hyphens.
_CARD = re.compile(r"\b(?:\d[ -]?){12,18}\d\b")


def _luhn_valid(number: str) -> bool:
    digits = [int(c) for c in number if c.isdigit()]
    if not 13 <= len(digits) <= 19:
        return False
    checksum = 0
    parity = len(digits) % 2
    for i, d in enumerate(digits):
        if i % 2 == parity:
            d *= 2
            if d > 9:
                d -= 9
        checksum += d
    return checksum % 10 == 0


class CreditCardScanner(PiiScanner):
    entity_type = "CREDIT_CARD"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _CARD.finditer(text):
            if not _luhn_valid(m.group()):
                continue
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    replacement=f"[CREDIT_CARD_{idx}]",
                )
            )
        return out
