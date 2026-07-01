"""GuardrailsInterceptor (F-QUA-01, F-QUA-02) — validate responses, act on failure.

An ExecutorPolicy so it can re-run the provider on failure. Runs every
validator against the response content; on failure it either raises, retries, or
warns. Records the outcome in ``ctx.guardrail_outcome`` for the audit trail.
"""

from __future__ import annotations

import logging

from ...context import InterceptorContext
from ...exceptions import GuardrailViolationError
from ...request import GavioRequest
from ...response import GavioResponse
from ...types import GuardrailOutcome
from ..chain import Executor
from ..executor import ExecutorPolicy
from .validator import OutputValidator

logger = logging.getLogger("gavio.guardrails")

_ACTIONS = {"error", "retry", "warn"}


class GuardrailsInterceptor(ExecutorPolicy):
    def __init__(
        self,
        validators: list[OutputValidator],
        on_failure: str = "error",
        max_retries: int = 2,
    ) -> None:
        if on_failure not in _ACTIONS:
            raise ValueError(f"on_failure must be one of {_ACTIONS}")
        self.validators = validators
        self.on_failure = on_failure
        self.max_retries = max_retries

    @property
    def name(self) -> str:
        return "guardrails"

    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        ctx.mark_fired(self.name)
        attempts = self.max_retries + 1 if self.on_failure == "retry" else 1
        response: GavioResponse | None = None
        failures: list[str] = []

        for attempt in range(attempts):
            response = await call_next(request)
            failures = [
                f"{v.name}: {result.reason}"
                for v in self.validators
                for result in [v.validate(response.content)]
                if not result.ok
            ]
            if not failures:
                ctx.guardrail_outcome = GuardrailOutcome.PASS.value
                return response
            logger.warning(
                "guardrails failed (attempt %d/%d): %s", attempt + 1, attempts, failures
            )

        ctx.guardrail_outcome = GuardrailOutcome.FAIL.value
        if self.on_failure == "warn":
            return response  # type: ignore[return-value]  # loop ran at least once
        raise GuardrailViolationError("; ".join(failures))
