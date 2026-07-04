"""SWIFT/BIC scanner — context-gated (FinTech pack).

Matches an 8- or 11-character BIC only when explicitly labelled ``SWIFT``/``BIC``,
so ordinary 8-letter uppercase words never trigger a false positive.
"""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

# Keyword classes keep the label case-insensitive while requiring an UPPERCASE
# code (real BICs are uppercase). Group 1 is the code.
_SWIFT = re.compile(
    r"\b(?:[Ss][Ww][Ii][Ff][Tt]|[Bb][Ii][Cc])(?:\s+[Cc]ode)?\s*[:#]?\s*"
    r"([A-Z]{6}[A-Z0-9]{2}(?:[A-Z0-9]{3})?)\b"
)


class SwiftBicScanner(PiiScanner):
    entity_type = "SWIFT_BIC"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _SWIFT.finditer(text):
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(1),
                    end=m.end(1),
                    value=m.group(1),
                    replacement=f"[SWIFT_BIC_{idx}]",
                )
            )
        return out
