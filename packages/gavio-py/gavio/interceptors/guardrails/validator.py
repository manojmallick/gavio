"""OutputValidator ABC for guardrails (F-QUA-01, F-QUA-02)."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass


@dataclass(frozen=True)
class ValidationResult:
    ok: bool
    reason: str = ""

    @staticmethod
    def passed() -> ValidationResult:
        return ValidationResult(True)

    @staticmethod
    def failed(reason: str) -> ValidationResult:
        return ValidationResult(False, reason)


class OutputValidator(ABC):
    """Validates a response's content string."""

    @property
    @abstractmethod
    def name(self) -> str:
        ...

    @abstractmethod
    def validate(self, content: str) -> ValidationResult:
        ...
