"""SemanticCache (F-CACHE-01, F-CACHE-02) — two-level cache as an ExecutorPolicy.

On the request path it checks an exact SHA-256 cache, then (optionally) a
semantic cosine-similarity cache; a hit returns the cached response and skips
the provider entirely. A miss calls the provider and stores the result.

Register it as the *outermost* executor policy (before retry/timeout/fallback)
so a hit short-circuits everything downstream.
"""

from __future__ import annotations

import hashlib
import json
import logging
import time

from ...context import InterceptorContext
from ...request import GavioRequest
from ...response import GavioResponse
from ...types import CacheType, TokenUsage
from ..chain import Executor
from ..executor import ExecutorPolicy
from .backend import CacheBackend
from .backends.memory import MemoryBackend
from .embedding import Embedder
from .vector import InMemoryVectorBackend, VectorBackend

logger = logging.getLogger("gavio.cache")


class SemanticCache(ExecutorPolicy):
    """Exact (SHA-256) + optional semantic (cosine) response cache."""

    def __init__(
        self,
        backend: CacheBackend | None = None,
        embedder: Embedder | None = None,
        vector_backend: VectorBackend | None = None,
        exact_ttl_seconds: int = 3600,
        semantic_ttl_seconds: int = 86400,
        similarity_threshold: float = 0.95,
        enable_semantic: bool | None = None,
    ) -> None:
        self._backend = backend or MemoryBackend()
        self._embedder = embedder
        # Semantic tier is on only when an embedder is available.
        self._semantic = embedder is not None if enable_semantic is None else enable_semantic
        if self._semantic and self._embedder is None:
            raise ValueError("enable_semantic=True requires an embedder")
        self._vector = vector_backend or (
            InMemoryVectorBackend() if self._semantic else None
        )
        self.exact_ttl_seconds = exact_ttl_seconds
        self.semantic_ttl_seconds = semantic_ttl_seconds
        self.similarity_threshold = similarity_threshold

    @property
    def name(self) -> str:
        return "semantic_cache"

    def _exact_key(self, request: GavioRequest) -> str:
        payload = {
            "provider": request.provider.value,
            "model": request.model,
            "messages": request.messages,
            "options": {k: request.options[k] for k in sorted(request.options)},
        }
        blob = json.dumps(payload, sort_keys=True, ensure_ascii=False)
        return "gavio:exact:" + hashlib.sha256(blob.encode("utf-8")).hexdigest()

    async def around(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        call_next: Executor,
    ) -> GavioResponse:
        ctx.mark_fired(self.name)
        start = time.monotonic()

        # 1. Exact match.
        exact_key = self._exact_key(request)
        entry = await self._backend.get(exact_key)
        if entry is not None:
            return self._hit(request, ctx, entry, CacheType.EXACT, start)

        # 2. Semantic match.
        embedding: list[float] | None = None
        if self._semantic and self._vector is not None and self._embedder is not None:
            embedding = self._embedder.embed(request.prompt_text())
            hit = await self._vector.query(embedding, self.similarity_threshold)
            if hit is not None:
                return self._hit(request, ctx, hit, CacheType.SEMANTIC, start)

        # 3. Miss — call the provider, then store.
        response = await call_next(request)
        entry = {
            "content": response.content,
            "model_version": response.model_version,
            "prompt_tokens": response.usage.prompt_tokens,
            "completion_tokens": response.usage.completion_tokens,
        }
        await self._backend.set(exact_key, entry, self.exact_ttl_seconds)
        if embedding is not None and self._vector is not None:
            await self._vector.add(embedding, entry, self.semantic_ttl_seconds)
        return response

    def _hit(
        self,
        request: GavioRequest,
        ctx: InterceptorContext,
        entry: dict,
        cache_type: CacheType,
        start: float,
    ) -> GavioResponse:
        ctx.cache_hit = True
        ctx.cache_type = cache_type.value
        latency_ms = int((time.monotonic() - start) * 1000)
        logger.info("cache %s hit (trace=%s)", cache_type.value, request.trace_id)
        return GavioResponse(
            trace_id=request.trace_id,
            content=entry["content"],
            model=request.model,
            provider=request.provider.value,
            model_version=entry.get("model_version", ""),
            usage=TokenUsage(
                prompt_tokens=entry.get("prompt_tokens", 0),
                completion_tokens=entry.get("completion_tokens", 0),
            ),
            cost_usd=0.0,  # cache hit = no provider cost
            latency_ms=latency_ms,
            cache_hit=True,
            cache_type=cache_type,
        )
