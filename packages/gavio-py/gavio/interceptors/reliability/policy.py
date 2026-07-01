"""ExecutorPolicy — interceptors that wrap the provider call itself.

Retry, timeout, and fallback can't be expressed as plain before/after hooks:
they need to re-invoke (or race) the executor. They subclass ``ExecutorPolicy``
so the Gateway composes them *around* the provider call, innermost-last.
"""

from __future__ import annotations

from abc import abstractmethod

from ...context import InterceptorContext
from ...interceptors.chain import Executor
from ...request import GavioRequest
from ...response import GavioResponse
from ..base import Interceptor


class ExecutorPolicy(Interceptor):
    """Base class for executor-wrapping reliability policies."""

    @abstractmethod
    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        """Invoke ``call_next`` (the wrapped executor) with this policy applied."""
        ...
