"""InspectorBus — synchronous fan-out of inspector events to subscribers."""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from typing import Any

logger = logging.getLogger("gavio.inspector")

Subscriber = Callable[[dict[str, Any]], None]


class InspectorBus:
    """Plain synchronous pub/sub for inspector events.

    ``emit`` is a no-op when there are no subscribers. A subscriber raising
    must never break the request — the error is swallowed, logged, and counted
    in :attr:`dropped`.
    """

    def __init__(self) -> None:
        self._subscribers: list[Subscriber] = []
        self._lock = threading.Lock()
        self._dropped = 0

    def subscribe(self, fn: Subscriber) -> None:
        with self._lock:
            self._subscribers.append(fn)

    def unsubscribe(self, fn: Subscriber) -> None:
        with self._lock:
            if fn in self._subscribers:
                self._subscribers.remove(fn)

    def emit(self, event: dict[str, Any]) -> None:
        with self._lock:
            subscribers = list(self._subscribers)
        if not subscribers:
            return
        for fn in subscribers:
            try:
                fn(event)
            except Exception:  # noqa: BLE001 - observation must never break a call
                self._dropped += 1
                logger.debug("inspector subscriber raised; event dropped", exc_info=True)

    @property
    def dropped(self) -> int:
        """Number of events dropped because a subscriber raised."""
        return self._dropped
