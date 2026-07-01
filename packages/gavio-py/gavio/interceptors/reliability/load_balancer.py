"""LoadBalancer (F-REL-04) — distribute calls across provider adapters."""

from __future__ import annotations

import itertools
import logging

from ...context import InterceptorContext
from ...interceptors.chain import Executor
from ...request import GavioRequest
from ...response import GavioResponse
from ...types import Provider
from ..executor import ExecutorPolicy

logger = logging.getLogger("gavio.loadbalancer")


class LoadBalancer(ExecutorPolicy):
    """Weighted round-robin across a pool of provider adapters.

    Register via ``.use(LoadBalancer([...]))``; it replaces the gateway's single
    provider for the call, routing each request to the next adapter in the
    weighted rotation. ``weights`` (parallel to ``adapters``) bias the rotation.
    """

    def __init__(
        self,
        adapters: list,
        weights: list[int] | None = None,
    ) -> None:
        if not adapters:
            raise ValueError("LoadBalancer requires at least one adapter")
        if weights is not None and len(weights) != len(adapters):
            raise ValueError("weights must match adapters length")
        weights = weights or [1] * len(adapters)
        # Expand by weight, then cycle for round-robin.
        expanded: list = []
        for adapter, weight in zip(adapters, weights, strict=True):
            expanded.extend([adapter] * max(1, weight))
        self._adapters = adapters
        self._cycle = itertools.cycle(expanded)

    @property
    def name(self) -> str:
        return "load_balancer"

    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        ctx.mark_fired(self.name)
        adapter = next(self._cycle)
        logger.debug("load balancing to %s", adapter.provider_name)
        rerouted = request.copy_with_messages(request.messages)
        rerouted.provider = Provider.coerce(adapter.provider_name)
        return await adapter.complete(rerouted)
