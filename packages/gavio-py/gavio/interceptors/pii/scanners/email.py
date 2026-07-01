"""Email address scanner (RFC 5322 pragmatic subset)."""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

_EMAIL = re.compile(
    r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b"
)


class EmailScanner(PiiScanner):
    entity_type = "EMAIL"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _EMAIL.finditer(text):
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    replacement=f"[EMAIL_{idx}]",
                )
            )
        return out
