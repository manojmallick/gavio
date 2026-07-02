"""Tests for the Redis cache backends (F-CACHE-04).

Skipped automatically when no Redis server is reachable — set
``GAVIO_TEST_REDIS_URL`` to point at a non-default instance (default:
``redis://localhost:6379``, matching the CI service container).
"""

from __future__ import annotations

import os
import socket
import uuid
from urllib.parse import urlparse

import pytest

from gavio import Gateway
from gavio.interceptors.cache import RedisBackend, RedisVectorBackend, SemanticCache
from gavio.interceptors.cache.embedding import HashingEmbedder
from gavio.providers.mock import MockProvider

REDIS_URL = os.environ.get("GAVIO_TEST_REDIS_URL", "redis://localhost:6379")


def _redis_available(url: str) -> bool:
    parsed = urlparse(url)
    addr = (parsed.hostname or "localhost", parsed.port or 6379)
    try:
        with socket.create_connection(addr, timeout=0.5):
            return True
    except OSError:
        return False


pytestmark = pytest.mark.skipif(
    not _redis_available(REDIS_URL), reason="redis not reachable at " + REDIS_URL
)


class CountingProvider(MockProvider):
    def __init__(self, response: str) -> None:
        super().__init__(response=response)
        self.calls = 0

    async def complete(self, request):
        self.calls += 1
        return await super().complete(request)


def _namespace() -> str:
    return f"gavio:test:{uuid.uuid4().hex}"


async def test_redis_backend_direct_roundtrip():
    backend = RedisBackend(url=REDIS_URL, namespace=_namespace())
    await backend.set("k1", {"v": 1})
    assert (await backend.get("k1")) == {"v": 1}
    await backend.delete("k1")
    assert (await backend.get("k1")) is None


async def test_redis_backend_ttl_expiry():
    backend = RedisBackend(url=REDIS_URL, namespace=_namespace())
    await backend.set("k1", {"v": 1}, ttl_seconds=1)
    assert (await backend.get("k1")) == {"v": 1}
    import asyncio

    await asyncio.sleep(1.3)
    assert (await backend.get("k1")) is None


async def test_redis_backend_clear_only_removes_its_own_keys():
    ns = _namespace()
    backend = RedisBackend(url=REDIS_URL, namespace=ns)
    other = RedisBackend(url=REDIS_URL, namespace=_namespace())
    await backend.set("a", 1)
    await backend.set("b", 2)
    await other.set("c", 3)
    await backend.clear()
    assert (await backend.get("a")) is None
    assert (await backend.get("b")) is None
    assert (await other.get("c")) == 3
    await other.clear()


async def test_redis_vector_backend_query_and_clear():
    vec = RedisVectorBackend(url=REDIS_URL, namespace=_namespace())
    await vec.add([1.0, 0.0], {"content": "a"})
    await vec.add([0.0, 1.0], {"content": "b"})
    hit = await vec.query([1.0, 0.0], threshold=0.9)
    assert hit == {"content": "a"}
    miss = await vec.query([0.0, -1.0], threshold=0.9)
    assert miss is None
    await vec.clear()
    assert (await vec.query([1.0, 0.0], threshold=0.0)) is None


async def test_semantic_cache_with_redis_backend_exact_hit():
    provider = CountingProvider("cached via redis")
    cache = SemanticCache(backend=RedisBackend(url=REDIS_URL, namespace=_namespace()))
    gw = Gateway.builder().adapter(provider).model("mock").use(cache).build()
    msgs = [{"role": "user", "content": "what is 2 + 2?"}]

    r1 = await gw.complete(messages=msgs)
    r2 = await gw.complete(messages=msgs)

    assert provider.calls == 1
    assert r1.cache_hit is False
    assert r2.cache_hit is True
    assert r2.cache_type.value == "exact"
    assert r2.content == r1.content


async def test_semantic_cache_with_redis_backends_semantic_hit():
    provider = CountingProvider("semantic via redis")
    ns = _namespace()
    cache = SemanticCache(
        backend=RedisBackend(url=REDIS_URL, namespace=f"{ns}:exact"),
        embedder=HashingEmbedder(),
        vector_backend=RedisVectorBackend(url=REDIS_URL, namespace=f"{ns}:vector"),
        similarity_threshold=0.95,
    )
    gw = Gateway.builder().adapter(provider).model("mock").use(cache).build()

    r1 = await gw.complete(messages=[{"role": "user", "content": "What is 2+2?"}])
    r2 = await gw.complete(messages=[{"role": "user", "content": "what is   2 + 2 ?"}])

    assert provider.calls == 1
    assert r1.cache_hit is False
    assert r2.cache_hit is True
    assert r2.cache_type.value == "semantic"
