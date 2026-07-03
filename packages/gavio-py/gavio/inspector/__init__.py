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

from .analytics import build_dag, build_sessions, build_stats
from .buffer import RingBuffer
from .bus import InspectorBus
from .config import InspectorConfig
from .emitter import TraceEmitter
from .export import export_trace, sanitize_messages
from .inspector import Inspector
from .server import InspectorServer
from .store import open_store, verify_chain_records

__all__ = [
    "Inspector",
    "InspectorBus",
    "InspectorConfig",
    "InspectorServer",
    "RingBuffer",
    "TraceEmitter",
    "build_dag",
    "build_sessions",
    "build_stats",
    "export_trace",
    "open_store",
    "sanitize_messages",
    "verify_chain_records",
]
