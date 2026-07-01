"""IBAN scanner — regex candidate + ISO 13616 mod-97 checksum validation."""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

# Candidate: 2 letters, 2 check digits, 11–30 alphanumerics (optionally spaced).
_IBAN = re.compile(r"\b[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]){11,30}\b")


def _valid_iban(candidate: str) -> bool:
    """ISO 13616 mod-97: rearrange, convert letters to numbers, check %97 == 1."""
    cleaned = candidate.replace(" ", "").upper()
    if len(cleaned) < 15:
        return False
    rearranged = cleaned[4:] + cleaned[:4]
    digits = "".join(
        str(ord(ch) - 55) if ch.isalpha() else ch for ch in rearranged
    )
    if not digits.isdigit():
        return False
    return int(digits) % 97 == 1


class IbanScanner(PiiScanner):
    entity_type = "IBAN"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _IBAN.finditer(text):
            if not _valid_iban(m.group()):
                continue
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    replacement=f"[IBAN_{idx}]",
                )
            )
        return out
