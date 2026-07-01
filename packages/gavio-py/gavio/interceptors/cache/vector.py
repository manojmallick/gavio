"""VectorBackend — nearest-neighbour store for the semantic cache (F-CACHE-02)."""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from collections import deque
from typing import Any

from .embedding import cosine_similarity


class VectorBackend(ABC):
    """Stores (vector, value) pairs and finds the nearest by cosine similarity."""

    @abstractmethod
    async def add(
        self, vector: list[float], value: Any, ttl_seconds: int | None = None
    ) -> None:
        ...

    @abstractmethod
    async def query(
        self, vector: list[float], threshold: float
    ) -> Any | None:
        """Return the value of the nearest entry with similarity >= threshold."""
        ...

    @abstractmethod
    async def clear(self) -> None:
        ...


class InMemoryVectorBackend(VectorBackend):
    """Bounded, brute-force in-memory vector store (default dev backend)."""

    def __init__(self, max_size: int = 1000) -> None:
        self.max_size = max_size
        # each entry: (vector, value, expires_at)
        self._items: deque[tuple[list[float], Any, float | None]] = deque(
            maxlen=max_size
        )

    async def add(
        self, vector: list[float], value: Any, ttl_seconds: int | None = None
    ) -> None:
        expires_at = time.monotonic() + ttl_seconds if ttl_seconds else None
        self._items.append((vector, value, expires_at))

    async def query(self, vector: list[float], threshold: float) -> Any | None:
        now = time.monotonic()
        best_value: Any | None = None
        best_sim = threshold
        for vec, value, expires_at in self._items:
            if expires_at is not None and now > expires_at:
                continue
            sim = cosine_similarity(vector, vec)
            if sim >= best_sim:
                best_sim = sim
                best_value = value
        return best_value

    async def clear(self) -> None:
        self._items.clear()

    def __len__(self) -> int:
        return len(self._items)
