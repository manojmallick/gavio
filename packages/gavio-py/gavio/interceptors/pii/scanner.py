"""PiiScanner abstract base class and ScannerRegistry."""

from __future__ import annotations

from abc import ABC, abstractmethod

from .context import ScanContext
from .match import PiiMatch


class PiiScanner(ABC):
    """Detects one class of PII entity within text.

    Scanners are tiered: tier 1 = regex, tier 2 = NER/ML, tier 3 = LLM. Lower
    tiers run first so cheap deterministic matches are found before expensive
    ones. v0.1.0 ships only tier-1 regex scanners.
    """

    @property
    @abstractmethod
    def entity_type(self) -> str:
        """e.g. 'EMAIL', 'IBAN', 'BSN'."""
        ...

    @property
    def tier(self) -> int:
        return 1

    @abstractmethod
    def scan(self, text: str, ctx: ScanContext) -> list[PiiMatch]:
        ...

    def confidence(self) -> float:
        return 1.0

    def supports_language(self, lang: str) -> bool:
        return True

    def supports_locale(self, locale: str) -> bool:
        return True


class ScannerRegistry:
    """Registry of scanners, discoverable by entity type at runtime."""

    def __init__(self, scanners: list[PiiScanner] | None = None) -> None:
        self._scanners: list[PiiScanner] = []
        for scanner in scanners or []:
            self.register(scanner)

    def register(self, scanner: PiiScanner) -> ScannerRegistry:
        self._scanners.append(scanner)
        return self

    def scanners(self) -> list[PiiScanner]:
        """Return scanners sorted by tier (lowest first)."""
        return sorted(self._scanners, key=lambda s: s.tier)

    def by_entity_type(self, entity_type: str) -> list[PiiScanner]:
        return [s for s in self._scanners if s.entity_type == entity_type]

    def __len__(self) -> int:
        return len(self._scanners)
