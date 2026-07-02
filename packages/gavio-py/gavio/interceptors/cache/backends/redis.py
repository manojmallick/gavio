"""Redis cache backends (F-CACHE-04) — production-grade distributed cache.

Optional — requires ``pip install gavio[redis]``. The in-memory backends
remain the zero-infra default (design principle P4); this module is only
imported when a caller actually constructs ``RedisBackend``/``RedisVectorBackend``.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

from ..backend import CacheBackend
from ..embedding import cosine_similarity
from ..vector import VectorBackend

try:
    from redis.asyncio import Redis as _AsyncRedis
except ImportError:  # pragma: no cover - exercised only without the extra installed
    _AsyncRedis = None  # type: ignore[assignment,misc]


def _require_redis() -> None:
    if _AsyncRedis is None:
        raise ImportError(
            "RedisBackend requires the 'redis' package — install with `pip install gavio[redis]`"
        )


class RedisBackend(CacheBackend):
    """Exact-match ``CacheBackend`` over Redis (F-CACHE-04).

    Keys are namespaced under an index set so ``clear()`` only removes entries
    this backend itself wrote, never the whole database.
    """

    def __init__(
        self,
        url: str = "redis://localhost:6379",
        namespace: str = "gavio:cache",
        client: Any | None = None,
    ) -> None:
        _require_redis()
        self._client = client or _AsyncRedis.from_url(url, decode_responses=True)
        self._prefix = f"{namespace}:"
        self._index_key = f"{namespace}:index"

    def _namespaced(self, key: str) -> str:
        return self._prefix + key

    async def get(self, key: str) -> Any | None:
        raw = await self._client.get(self._namespaced(key))
        if raw is None:
            await self._client.srem(self._index_key, key)
            return None
        return json.loads(raw)

    async def set(self, key: str, value: Any, ttl_seconds: int | None = None) -> None:
        raw = json.dumps(value)
        if ttl_seconds:
            await self._client.set(self._namespaced(key), raw, ex=ttl_seconds)
        else:
            await self._client.set(self._namespaced(key), raw)
        await self._client.sadd(self._index_key, key)

    async def delete(self, key: str) -> None:
        await self._client.delete(self._namespaced(key))
        await self._client.srem(self._index_key, key)

    async def clear(self) -> None:
        keys = await self._client.smembers(self._index_key)
        if keys:
            await self._client.delete(*(self._namespaced(str(k)) for k in keys))
        await self._client.delete(self._index_key)


class RedisVectorBackend(VectorBackend):
    """Brute-force cosine-similarity ``VectorBackend`` over Redis (F-CACHE-04).

    Same brute-force matching strategy as ``InMemoryVectorBackend`` — just
    shared across processes. Fine for the cache's scale (bounded, TTL'd
    entries); not a substitute for a real vector database.
    """

    def __init__(
        self,
        url: str = "redis://localhost:6379",
        namespace: str = "gavio:vector",
        client: Any | None = None,
    ) -> None:
        _require_redis()
        self._client = client or _AsyncRedis.from_url(url, decode_responses=True)
        self._namespace = namespace
        self._index_key = f"{namespace}:index"

    async def add(
        self, vector: list[float], value: Any, ttl_seconds: int | None = None
    ) -> None:
        entry_id = uuid.uuid4().hex
        key = f"{self._namespace}:{entry_id}"
        raw = json.dumps({"vector": vector, "value": value})
        if ttl_seconds:
            await self._client.set(key, raw, ex=ttl_seconds)
        else:
            await self._client.set(key, raw)
        await self._client.sadd(self._index_key, entry_id)

    async def query(self, vector: list[float], threshold: float) -> Any | None:
        entry_ids = await self._client.smembers(self._index_key)
        best_value: Any | None = None
        best_sim = threshold
        for entry_id in entry_ids:
            entry_id = str(entry_id)
            key = f"{self._namespace}:{entry_id}"
            raw = await self._client.get(key)
            if raw is None:
                await self._client.srem(self._index_key, entry_id)
                continue
            entry = json.loads(raw)
            sim = cosine_similarity(vector, entry["vector"])
            if sim >= best_sim:
                best_sim = sim
                best_value = entry["value"]
        return best_value

    async def clear(self) -> None:
        entry_ids = await self._client.smembers(self._index_key)
        keys = [f"{self._namespace}:{entry_id!s}" for entry_id in entry_ids]
        if keys:
            await self._client.delete(*keys)
        await self._client.delete(self._index_key)
