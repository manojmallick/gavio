"""Tests for the SemanticCache interceptor (F-CACHE-01/02/03)."""

from __future__ import annotations

from gavio import Gateway
from gavio.interceptors.cache import (
    HashingEmbedder,
    MemoryBackend,
    SemanticCache,
    cosine_similarity,
)
from gavio.interceptors.cache.embedding import HashingEmbedder as HE
from gavio.providers.mock import MockProvider


class CountingProvider(MockProvider):
    """MockProvider that counts how many times the provider was actually hit."""

    def __init__(self, response: str) -> None:
        super().__init__(response=response)
        self.calls = 0

    async def complete(self, request):
        self.calls += 1
        return await super().complete(request)


def _gw(provider, cache):
    return Gateway.builder().adapter(provider).model("mock").use(cache).build()


async def test_exact_cache_hit_skips_provider():
    provider = CountingProvider("cached answer")
    gw = _gw(provider, SemanticCache())
    msgs = [{"role": "user", "content": "what is 2 + 2?"}]

    r1 = await gw.complete(messages=msgs)
    r2 = await gw.complete(messages=msgs)

    assert provider.calls == 1  # second call served from cache
    assert r1.cache_hit is False
    assert r2.cache_hit is True
    assert r2.cache_type is not None and r2.cache_type.value == "exact"
    assert r2.content == r1.content
    assert r2.cost_usd == 0.0


async def test_exact_cache_miss_on_different_prompt():
    provider = CountingProvider("x")
    gw = _gw(provider, SemanticCache())
    await gw.complete(messages=[{"role": "user", "content": "alpha"}])
    await gw.complete(messages=[{"role": "user", "content": "beta"}])
    assert provider.calls == 2


async def test_semantic_cache_hits_on_whitespace_variant():
    provider = CountingProvider("semantic answer")
    cache = SemanticCache(embedder=HashingEmbedder(), similarity_threshold=0.95)
    gw = _gw(provider, cache)

    r1 = await gw.complete(messages=[{"role": "user", "content": "What is 2+2?"}])
    # different bytes (case/spacing/punctuation) → exact miss, semantic hit
    r2 = await gw.complete(messages=[{"role": "user", "content": "what is   2 + 2 ?"}])

    assert provider.calls == 1
    assert r1.cache_hit is False
    assert r2.cache_hit is True
    assert r2.cache_type.value == "semantic"


async def test_semantic_disabled_without_embedder():
    provider = CountingProvider("x")
    cache = SemanticCache()  # no embedder → semantic off
    gw = _gw(provider, cache)
    await gw.complete(messages=[{"role": "user", "content": "What is 2+2?"}])
    await gw.complete(messages=[{"role": "user", "content": "what is 2 + 2 ?"}])
    assert provider.calls == 2  # only exact matching, so both miss


def test_hashing_embedder_and_cosine():
    e = HE()
    a = e.embed("What is 2+2?")
    b = e.embed("what is   2 + 2 ?")
    c = e.embed("completely different sentence about cats")
    assert cosine_similarity(a, b) > 0.99  # same tokens
    assert cosine_similarity(a, c) < 0.5


async def test_memory_backend_direct():
    b = MemoryBackend(max_size=2)
    await b.set("k1", {"v": 1})
    assert (await b.get("k1")) == {"v": 1}
    await b.set("k2", {"v": 2})
    await b.set("k3", {"v": 3})  # evicts k1 (LRU, max_size=2)
    assert (await b.get("k1")) is None
    assert (await b.get("k3")) == {"v": 3}
