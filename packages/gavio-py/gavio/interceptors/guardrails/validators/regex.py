"""Regex allow/deny validators (F-QUA-02)."""

from __future__ import annotations

import re

from ..validator import OutputValidator, ValidationResult


class RegexDenylistValidator(OutputValidator):
    """Fails if the content matches ANY denied pattern."""

    def __init__(self, patterns: list[str]) -> None:
        self._patterns = [re.compile(p) for p in patterns]

    @property
    def name(self) -> str:
        return "regex_denylist"

    def validate(self, content: str) -> ValidationResult:
        for pattern in self._patterns:
            if pattern.search(content):
                return ValidationResult.failed(
                    f"content matched denied pattern /{pattern.pattern}/"
                )
        return ValidationResult.passed()


class RegexAllowlistValidator(OutputValidator):
    """Fails unless the content matches at least ONE allowed pattern."""

    def __init__(self, patterns: list[str]) -> None:
        self._patterns = [re.compile(p) for p in patterns]

    @property
    def name(self) -> str:
        return "regex_allowlist"

    def validate(self, content: str) -> ValidationResult:
        if any(p.search(content) for p in self._patterns):
            return ValidationResult.passed()
        return ValidationResult.failed("content matched no allowed pattern")
