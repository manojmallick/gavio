"""CacheBackend ABC — the key/value contract behind the cache interceptors.

The full SemanticCache interceptor lands in v0.2.0 (F-CACHE-01/02). v0.1.0
ships the backend interface and the in-memory backend so dev mode has a
working, dependency-free cache substrate.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any


class CacheBackend(ABC):
    """A minimal async key/value store."""

    @abstractmethod
    async def get(self, key: str) -> Any | None:
        ...

    @abstractmethod
    async def set(self, key: str, value: Any, ttl_seconds: int | None = None) -> None:
        ...

    @abstractmethod
    async def delete(self, key: str) -> None:
        ...

    @abstractmethod
    async def clear(self) -> None:
        ...
