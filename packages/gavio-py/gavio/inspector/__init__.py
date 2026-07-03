"""Gavio Inspector (F-DX-09 / F-DX-10) — live pipeline visibility.

Enable via the builder::

    gw = Gateway.builder().dev_mode(True).inspect(True).build()
    print(gw.inspector.server.port)

Or with explicit settings::

    from gavio.inspector import InspectorConfig
    gw = (
        Gateway.builder()
        .provider("openai")
        .inspect(InspectorConfig(mode="metadata", port=7411))
        .build()
    )

Off by default — dev mode alone never starts the inspector.
"""

from __future__ import annotations

from .buffer import RingBuffer
from .bus import InspectorBus
from .config import InspectorConfig
from .emitter import TraceEmitter
from .inspector import Inspector
from .server import InspectorServer

__all__ = [
    "Inspector",
    "InspectorBus",
    "InspectorConfig",
    "InspectorServer",
    "RingBuffer",
    "TraceEmitter",
]
