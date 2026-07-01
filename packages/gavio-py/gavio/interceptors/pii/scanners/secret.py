"""Secret / credential scanner (F-SEC-04).

Detects API keys, tokens, JWTs, PEM private keys, and database connection
strings. These must never leave the device, so SecretScanner is tier 1 and
runs by default.
"""

from __future__ import annotations

import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

# (label, compiled pattern) — ordered most-specific first.
_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("ANTHROPIC_KEY", re.compile(r"\bsk-ant-[A-Za-z0-9_\-]{20,}\b")),
    ("OPENAI_KEY", re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_\-]{20,}\b")),
    ("AWS_ACCESS_KEY", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("GITHUB_TOKEN", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b")),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\b")),
    (
        "PRIVATE_KEY",
        re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
    ),
    (
        "DB_CONNECTION_STRING",
        re.compile(r"\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis)://[^\s\"']+"),
    ),
]


class SecretScanner(PiiScanner):
    entity_type = "SECRET"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for _label, pattern in _PATTERNS:
            for m in pattern.finditer(text):
                idx = ctx.next_index(self.entity_type)
                out.append(
                    PiiMatch(
                        entity_type=self.entity_type,
                        start=m.start(),
                        end=m.end(),
                        value=m.group(),
                        replacement=f"[SECRET_{idx}]",
                    )
                )
        return out
