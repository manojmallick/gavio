"""US Social Security Number scanner."""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

# AAA-GG-SSSS with hyphens or spaces. Requires a separator to avoid colliding
# with bare 9-digit numbers (handled by BsnScanner / others).
_SSN = re.compile(r"\b(?!000|666|9\d\d)\d{3}[ -](?!00)\d{2}[ -](?!0000)\d{4}\b")


class SsnScanner(PiiScanner):
    entity_type = "SSN"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _SSN.finditer(text):
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    replacement=f"[SSN_{idx}]",
                )
            )
        return out
