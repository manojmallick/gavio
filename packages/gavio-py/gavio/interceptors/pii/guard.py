"""PiiGuard — the pre/post interceptor that detects and redacts PII.

Pipeline rule (MASTER_PLAN P5 / privacy): PII is scanned on every request
before it reaches the provider. Detected entities are redacted/masked/tagged
or blocked. In REDACT mode the original values are restored in the response.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any

from ...context import InterceptorContext
from ...exceptions import PiiBlockedError
from ...request import GavioRequest
from ...response import GavioResponse
from ...types import PiiMode, Sensitivity
from ..base import Interceptor
from .context import ScanContext
from .match import PiiMatch
from .scanner import PiiScanner
from .scanners import default_scanners

if TYPE_CHECKING:
    from .policy_pack import PolicyPack

logger = logging.getLogger("gavio.pii")

_STATE_KEY = "pii_replacements"

# Confidence floor per sensitivity level — matches below the floor are ignored.
_CONFIDENCE_FLOOR = {
    Sensitivity.STRICT: 0.0,
    Sensitivity.BALANCED: 0.6,
    Sensitivity.PERMISSIVE: 0.9,
}


class PiiGuard(Interceptor):
    """Scan request messages for PII and act per the configured mode."""

    def __init__(
        self,
        scanners: list[PiiScanner] | None = None,
        sensitivity: Sensitivity | str = Sensitivity.STRICT,
        mode: PiiMode | str = PiiMode.REDACT,
        restore_on_response: bool = True,
        log_entity_types: bool = True,
        dry_run: bool = False,
        locale: str = "NL",
        language: str = "en",
    ) -> None:
        self._scanners = scanners if scanners is not None else default_scanners()
        self.sensitivity = Sensitivity(sensitivity)
        self.mode = PiiMode(mode)
        self.restore_on_response = restore_on_response
        self.log_entity_types = log_entity_types
        self._dry_run = dry_run
        self.locale = locale
        self.language = language

    @property
    def name(self) -> str:
        return "pii_guard"

    @property
    def dry_run_safe(self) -> bool:
        return True

    @classmethod
    def from_policy_pack(cls, *packs: PolicyPack, **kwargs: Any) -> PiiGuard:
        from .policy_pack import policy_pack_scanners

        return cls(scanners=policy_pack_scanners(*packs), **kwargs)

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        scan_ctx = ScanContext(language=self.language, locale=self.locale)
        floor = _CONFIDENCE_FLOOR[self.sensitivity]

        new_messages: list[dict[str, str]] = []
        all_types: list[str] = []
        replacements: dict[str, str] = ctx.state.get(_STATE_KEY, {})

        for message in request.messages:
            content = message.get("content", "")
            matches = self._scan_text(content, scan_ctx, floor)
            all_types.extend(m.entity_type for m in matches)

            if matches and self.mode == PiiMode.BLOCK:
                types = [m.entity_type for m in matches]
                logger.warning("pii_guard BLOCK: %s", sorted(set(types)))
                raise PiiBlockedError(types)

            redacted = content
            if matches and not (self._dry_run or ctx.dry_run):
                redacted = self._apply(content, matches, replacements)

            new_msg = dict(message)
            new_msg["content"] = redacted
            new_messages.append(new_msg)

        if all_types:
            ctx.record_pii(all_types)
            if self.log_entity_types:
                logger.info(
                    "pii_guard detected entity types: %s", sorted(set(all_types))
                )

        if self.restore_on_response and replacements:
            ctx.state[_STATE_KEY] = replacements

        if self._dry_run or ctx.dry_run:
            return request
        return request.copy_with_messages(new_messages)

    async def after(
        self, response: GavioResponse, ctx: InterceptorContext
    ) -> GavioResponse:
        if not self.restore_on_response or self.mode != PiiMode.REDACT:
            return response
        replacements: dict[str, str] = ctx.state.get(_STATE_KEY, {})
        if not replacements:
            return response
        content = response.content
        # Replace placeholder tokens back with original values.
        for token, original in replacements.items():
            content = content.replace(token, original)
        if content == response.content:
            return response
        return response.copy_with_content(content)

    def _scan_text(
        self, text: str, scan_ctx: ScanContext, floor: float
    ) -> list[PiiMatch]:
        raw: list[PiiMatch] = []
        for scanner in sorted(self._scanners, key=lambda s: s.tier):
            for match in scanner.scan(text, scan_ctx):
                if match.confidence >= floor:
                    raw.append(match)
        return _resolve_overlaps(raw)

    def _apply(
        self,
        text: str,
        matches: list[PiiMatch],
        replacements: dict[str, str],
    ) -> str:
        # Replace right-to-left so earlier offsets stay valid.
        for match in sorted(matches, key=lambda m: m.start, reverse=True):
            token = self._token_for(match)
            if self.mode == PiiMode.REDACT:
                replacements[token] = match.value
            text = text[: match.start] + token + text[match.end :]
        return text

    def _token_for(self, match: PiiMatch) -> str:
        if self.mode == PiiMode.MASK:
            return "*" * max(match.length, 1)
        if self.mode == PiiMode.TAG:
            return f"<{match.entity_type}>{match.value}</{match.entity_type}>"
        # REDACT (default)
        return match.replacement or f"[{match.entity_type}]"


def _resolve_overlaps(matches: list[PiiMatch]) -> list[PiiMatch]:
    """Drop lower-priority matches that overlap a kept one.

    Sort by start, then by descending span length (prefer the longer match),
    then by confidence. Greedily keep non-overlapping matches.
    """
    ordered = sorted(matches, key=lambda m: (m.start, -m.length, -m.confidence))
    kept: list[PiiMatch] = []
    occupied_end = -1
    for match in ordered:
        if match.start >= occupied_end:
            kept.append(match)
            occupied_end = match.end
    return kept
