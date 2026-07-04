"""License / copyright detection validator (F-QUA-10).

Flags known open-source license text (MIT, Apache-2.0, GPL-2.0/3.0,
BSD-3-Clause, MPL-2.0) in a model response before it lands in user code.
Matches against a shipped corpus of hashed 8-word shingles -- no license text
is ever bundled. Detections surface in the guardrail outcome and, via the
guardrails interceptor, in the audit record.
"""

from __future__ import annotations

import hashlib

from ..validator import OutputValidator, ValidationResult
from ._license_fingerprints import LICENSE_FINGERPRINTS

_SHINGLE_N = 8


def _normalize_tokens(text: str) -> list[str]:
    """ASCII-lower-alnum tokeniser -- must stay byte-identical across all SDKs."""
    out: list[str] = []
    cur: list[str] = []
    for ch in text:
        o = ord(ch)
        if 65 <= o <= 90:
            cur.append(chr(o + 32))
        elif (97 <= o <= 122) or (48 <= o <= 57):
            cur.append(ch)
        elif cur:
            out.append("".join(cur))
            cur = []
    if cur:
        out.append("".join(cur))
    return out


def _shingle_hashes(tokens: list[str]) -> set[str]:
    hashes: set[str] = set()
    for i in range(len(tokens) - _SHINGLE_N + 1):
        gram = " ".join(tokens[i : i + _SHINGLE_N])
        hashes.add(hashlib.sha256(gram.encode("utf-8")).hexdigest()[:16])
    return hashes


def detect_licenses(
    content: str,
    licenses: list[str] | None = None,
    min_matches: int = 1,
) -> list[str]:
    """Return the sorted SPDX ids whose fingerprint appears in ``content``."""
    present = _shingle_hashes(_normalize_tokens(content))
    ids = licenses if licenses is not None else list(LICENSE_FINGERPRINTS)
    found: list[str] = []
    for spdx_id in ids:
        fingerprints = LICENSE_FINGERPRINTS.get(spdx_id)
        if not fingerprints:
            continue
        hits = sum(1 for h in fingerprints if h in present)
        if hits >= min_matches:
            found.append(spdx_id)
    return sorted(found)


class LicenseDetectorValidator(OutputValidator):
    """Fails if the content contains recognisable license text (F-QUA-10)."""

    def __init__(
        self,
        licenses: list[str] | None = None,
        min_matches: int = 1,
    ) -> None:
        self._licenses = licenses
        self._min_matches = min_matches

    @property
    def name(self) -> str:
        return "license_detector"

    def detect(self, content: str) -> list[str]:
        return detect_licenses(content, self._licenses, self._min_matches)

    def validate(self, content: str) -> ValidationResult:
        found = self.detect(content)
        if not found:
            return ValidationResult.passed()
        return ValidationResult.failed(f"license text detected: {', '.join(found)}")
