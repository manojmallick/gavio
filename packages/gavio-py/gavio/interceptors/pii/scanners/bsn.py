"""Dutch BSN scanner — regex + 11-proef (eleven-test) checksum."""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

# BSN is 8 or 9 digits; we validate 9-digit form with the 11-proef.
_BSN = re.compile(r"\b\d{9}\b")


def _valid_bsn(digits: str) -> bool:
    """11-proef: sum of digit*weight (9,8,...,2,-1) must be divisible by 11."""
    if len(digits) != 9:
        return False
    weights = [9, 8, 7, 6, 5, 4, 3, 2, -1]
    total = sum(int(d) * w for d, w in zip(digits, weights, strict=True))
    return total % 11 == 0


class BsnScanner(PiiScanner):
    entity_type = "BSN"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _BSN.finditer(text):
            if not _valid_bsn(m.group()):
                continue
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    replacement=f"[BSN_{idx}]",
                )
            )
        return out
