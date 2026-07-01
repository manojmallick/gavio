"""In-memory cache backend (F-CACHE-03) — default zero-dependency dev backend."""

from __future__ import annotations

import time
from collections import OrderedDict
from typing import Any

from ..backend import CacheBackend


class MemoryBackend(CacheBackend):
    """LRU-bounded, optionally TTL'd in-process cache. Not shared across processes."""

    def __init__(self, max_size: int = 1000) -> None:
        self.max_size = max_size
        self._store: OrderedDict[str, tuple[Any, float | None]] = OrderedDict()

    async def get(self, key: str) -> Any | None:
        entry = self._store.get(key)
        if entry is None:
            return None
        value, expires_at = entry
        if expires_at is not None and time.monotonic() > expires_at:
            del self._store[key]
            return None
        self._store.move_to_end(key)
        return value

    async def set(self, key: str, value: Any, ttl_seconds: int | None = None) -> None:
        expires_at = time.monotonic() + ttl_seconds if ttl_seconds else None
        self._store[key] = (value, expires_at)
        self._store.move_to_end(key)
        while len(self._store) > self.max_size:
            self._store.popitem(last=False)

    async def delete(self, key: str) -> None:
        self._store.pop(key, None)

    async def clear(self) -> None:
        self._store.clear()

    def __len__(self) -> int:
        return len(self._store)
