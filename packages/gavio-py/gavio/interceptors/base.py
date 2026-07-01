"""The Interceptor abstract base class — the unit of composition in Gavio."""

from __future__ import annotations

from abc import ABC, abstractmethod

from ..context import InterceptorContext
from ..request import GavioRequest
from ..response import GavioResponse


class Interceptor(ABC):
    """A pre/post hook around the provider call.

    ``before`` runs in registration order on the request; ``after`` runs in
    reverse order on the response. Either may be a no-op. Raising from
    ``before`` aborts the call.
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """Unique identifier, recorded in audit logs."""
        ...

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        """Pre-interceptor. Return a (possibly modified) request or raise to abort."""
        return request

    async def after(
        self, response: GavioResponse, ctx: InterceptorContext
    ) -> GavioResponse:
        """Post-interceptor. Return a (possibly modified) response."""
        return response

    async def on_error(self, error: Exception, ctx: InterceptorContext) -> None:
        """Called if the provider call or a downstream interceptor raises."""
        return None

    @property
    def dry_run_safe(self) -> bool:
        """If True, this interceptor still runs in dry-run mode (logs only)."""
        return True
