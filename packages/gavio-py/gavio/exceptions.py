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


class MalformedToolCallError(ProviderError):
    """The provider returned a tool call whose arguments are not a JSON object.

    The message names the tool only — never the arguments, which may hold PII.
    """


class ProviderUnavailableError(ProviderError):
    """The provider could not be reached (network / health-check failure)."""


class RateLimitError(ProviderError):
    """The provider returned a rate-limit (HTTP 429) signal."""


class CircuitOpenError(ProviderUnavailableError):
    """The circuit breaker is open; the call was rejected without hitting the provider."""


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


class LoopGuardError(GavioError):
    """A loop guard stopped a run that reached one of its limits (F-REL-09).

    The message names the limit and the run's counts, never prompts or tool arguments.
    ``decision`` is the :class:`~gavio.interceptors.reliability.loop_guard.LoopGuardDecision`
    wire dict, for callers that want to react (log, alert, ask a human).
    """

    def __init__(self, message: str, decision: dict | None = None) -> None:
        super().__init__(message)
        self.decision: dict = dict(decision or {})


class RateLimitExceededError(GavioError):
    """A local rate limit (requests/tokens per minute) was exceeded."""


class ModelNotAllowedError(GavioError):
    """The caller's role is not permitted to use the requested model (RBAC)."""

    def __init__(self, role: str, model: str) -> None:
        self.role = role
        self.model = model
        super().__init__(f"role {role!r} may not use model {model!r}")


class GuardrailViolationError(GavioError):
    """Output failed a guardrail validator with on_failure='error'."""


class ToolRuntimeError(GavioError):
    """Tool Runtime blocked invalid or stale tool context."""


class PromptInjectionError(GavioError):
    """A prompt-injection attempt was detected and the guard is in block mode."""

    def __init__(self, patterns: list[str]) -> None:
        self.patterns = patterns
        super().__init__(f"prompt injection detected: {patterns}")
