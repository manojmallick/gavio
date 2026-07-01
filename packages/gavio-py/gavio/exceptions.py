"""Gavio exception hierarchy.

All Gavio errors derive from :class:`GavioError` so callers can catch the
whole family with a single ``except``.
"""

from __future__ import annotations


class GavioError(Exception):
    """Base class for every error raised by Gavio."""


class ConfigurationError(GavioError):
    """Raised when the gateway is misconfigured (e.g. no provider set)."""


class ProviderError(GavioError):
    """Base class for provider-adapter failures."""


class ProviderUnavailableError(ProviderError):
    """The provider could not be reached (network / health-check failure)."""


class RateLimitError(ProviderError):
    """The provider returned a rate-limit (HTTP 429) signal."""


class ServerError(ProviderError):
    """The provider returned a 5xx server error."""


class TimeoutError(ProviderError):  # noqa: A001 - intentional domain name
    """A request exceeded its configured timeout."""


class PiiBlockedError(GavioError):
    """PiiGuard is in BLOCK mode and detected PII in the request."""

    def __init__(self, entity_types: list[str]) -> None:
        self.entity_types = entity_types
        super().__init__(
            f"Request blocked: PII detected ({', '.join(sorted(set(entity_types)))})"
        )


class BudgetExceededError(GavioError):
    """A hard budget cap was exceeded. Never swallow this — surface to user."""


class GuardrailViolationError(GavioError):
    """Output failed a guardrail validator with on_failure='error'."""
