"""ExecutorPolicy — interceptors that wrap the provider call itself.

Some features can't be expressed as plain before/after hooks: they need to
re-invoke, race, or *skip* the provider call. Retry, timeout, fallback (F-REL-*)
and the cache (F-CACHE-*) subclass ``ExecutorPolicy`` so the Gateway composes
them around the provider call, innermost-last. On a cache hit, ``around`` simply
returns without calling ``call_next``.
"""

from __future__ import annotations

from abc import abstractmethod

from ..context import InterceptorContext
from ..request import GavioRequest
from ..response import GavioResponse
from .base import Interceptor
from .chain import Executor


class ExecutorPolicy(Interceptor):
    """Base class for executor-wrapping policies."""

    @abstractmethod
    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        """Invoke ``call_next`` (the wrapped executor) with this policy applied."""
        ...
