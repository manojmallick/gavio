"""Inspector — the composite of bus, ring buffer, and optional HTTP server."""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from .buffer import RingBuffer
from .bus import InspectorBus
from .config import InspectorConfig
from .emitter import TraceEmitter
from .server import InspectorServer

ReplayHandler = Callable[..., Awaitable[GavioResponse]]

_AUDIT_BEFORE_PII_LINT = "audit registered before pii_guard — audit will hash unredacted prompts"
_CACHE_BEFORE_PII_LINT = "cache registered before pii_guard — raw PII used as cache key"


def compute_lints(interceptor_names: list[str]) -> list[dict[str, str]]:
    """Static pipeline-ordering lints shown on the inspector's pipeline view."""
    lints: list[dict[str, str]] = []
    if "pii_guard" not in interceptor_names:
        return lints
    pii_index = interceptor_names.index("pii_guard")
    if "audit" in interceptor_names and interceptor_names.index("audit") < pii_index:
        lints.append({"level": "warning", "message": _AUDIT_BEFORE_PII_LINT})
    cache_index = next((i for i, name in enumerate(interceptor_names) if "cache" in name), None)
    if cache_index is not None and cache_index < pii_index:
        lints.append({"level": "warning", "message": _CACHE_BEFORE_PII_LINT})
    return lints


class Inspector:
    """Live view over one Gateway's request pipeline (F-DX-09 / F-DX-10).

    Holds the event bus and the bounded trace buffer; optionally serves them
    over HTTP for the vendored web UI. Created by the GatewayBuilder when
    inspection is enabled — never implicitly.
    """

    def __init__(
        self,
        config: InspectorConfig,
        pipeline: dict[str, Any],
        *,
        dev_mode: bool = False,
    ) -> None:
        self.config = config
        self.mode = config.resolve_mode(dev_mode)
        self.pipeline = pipeline
        self.bus = InspectorBus()
        self.buffer = RingBuffer(max_traces=config.max_traces)
        self.bus.subscribe(self.buffer.on_event)
        self.server: InspectorServer | None = None
        # Wired by the Gateway: /api/replay re-fires through the live pipeline.
        self.replay_handler: ReplayHandler | None = None
        # Used by /api/simulate-cost; the builder passes its PricingProvider.
        self.pricing = PricingProvider()
        # Set in store mode (gavio inspect --store); enables /api/chain/verify.
        self.audit_records: list[dict[str, Any]] | None = None

    def emitter(self, request: GavioRequest) -> TraceEmitter:
        """Create the per-request event emitter."""
        return TraceEmitter(self.bus, self.mode, request.trace_id)

    def start_server(self) -> None:
        if self.server is None:
            self.server = InspectorServer(self)
            self.server.start()

    def stop(self) -> None:
        if self.server is not None:
            self.server.stop()
            self.server = None
