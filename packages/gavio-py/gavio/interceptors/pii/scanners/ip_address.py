"""IP address scanner — IPv4 and IPv6, validated via the ipaddress stdlib."""

from __future__ import annotations

import ipaddress
import re

from ..context import ScanContext
from ..match import PiiMatch
from ..scanner import PiiScanner

_IPV4 = r"(?:\d{1,3}\.){3}\d{1,3}"
# Permissive IPv6 candidate — allows empty groups for "::" compression. False
# positives are filtered by ipaddress validation below.
_IPV6 = r"(?:[A-Fa-f0-9]{0,4}:){2,7}[A-Fa-f0-9]{0,4}"
_IP = re.compile(rf"(?<![\w.])(?:{_IPV6}|{_IPV4})(?![\w.])")


def _valid_ip(candidate: str) -> bool:
    try:
        ipaddress.ip_address(candidate)
        return True
    except ValueError:
        return False


class IpAddressScanner(PiiScanner):
    entity_type = "IP_ADDRESS"

    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        out: list[PiiMatch] = []
        for m in _IP.finditer(text):
            if not _valid_ip(m.group()):
                continue
            idx = ctx.next_index(self.entity_type)
            out.append(
                PiiMatch(
                    entity_type=self.entity_type,
                    start=m.start(),
                    end=m.end(),
                    value=m.group(),
                    replacement=f"[IP_ADDRESS_{idx}]",
                )
            )
        return out
