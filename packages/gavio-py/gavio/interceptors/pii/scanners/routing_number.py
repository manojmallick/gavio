"""US ABA routing-number scanner — 9 digits + mod-10 checksum (FinTech pack)."""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

_ROUTING = re.compile(r"\b\d{9}\b")
_WEIGHTS = (3, 7, 1, 3, 7, 1, 3, 7, 1)


def valid_routing_number(candidate: str) -> bool:
    """ABA checksum: weighted digit sum must be a non-zero multiple of 10."""
    if len(candidate) != 9 or not candidate.isdigit():
        return False
    total = sum(w * int(d) for w, d in zip(_WEIGHTS, candidate, strict=True))
    return total > 0 and total % 10 == 0


class RoutingNumberScanner(PiiScanner):
    entity_type = "ROUTING_NUMBER"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _ROUTING.finditer(text):
            if not valid_routing_number(m.group()):
                continue
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    replacement=f"[ROUTING_NUMBER_{idx}]",
                )
            )
        return out
