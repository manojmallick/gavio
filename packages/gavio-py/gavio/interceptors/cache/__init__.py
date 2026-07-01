"""Caching (F-CACHE-01 exact, F-CACHE-02 semantic, F-CACHE-03 in-memory)."""

from __future__ import annotations

from .backend import CacheBackend
from .backends import MemoryBackend
from .embedding import Embedder, HashingEmbedder, cosine_similarity
from .interceptor import SemanticCache
from .vector import InMemoryVectorBackend, VectorBackend

__all__ = [
    "SemanticCache",
    "CacheBackend",
    "MemoryBackend",
    "VectorBackend",
    "InMemoryVectorBackend",
    "Embedder",
    "HashingEmbedder",
    "cosine_similarity",
]
