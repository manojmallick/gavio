"""Embeddings for the semantic cache (F-CACHE-02).

The core stays dependency-free, so it ships a deterministic hashing embedder
(hashed bag-of-words, L2-normalised) — good enough to dedup near-identical
prompts (case/whitespace/punctuation variants) with zero dependencies. For
production semantic matching, plug in a real embedder (e.g. an OpenAI
``text-embedding-3-small`` adapter) that implements the same ``Embedder``
protocol.
"""

from __future__ import annotations

import hashlib
import math
import re
from typing import Protocol, runtime_checkable

_TOKEN = re.compile(r"[a-z0-9]+")


@runtime_checkable
class Embedder(Protocol):
    """Turns text into a fixed-length float vector."""

    def embed(self, text: str) -> list[float]: ...


class HashingEmbedder:
    """Zero-dependency hashed bag-of-words embedder (L2-normalised)."""

    def __init__(self, dim: int = 256) -> None:
        self.dim = dim

    def embed(self, text: str) -> list[float]:
        vec = [0.0] * self.dim
        for token in _TOKEN.findall(text.lower()):
            digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
            bucket = int.from_bytes(digest, "big") % self.dim
            vec[bucket] += 1.0
        norm = math.sqrt(sum(x * x for x in vec))
        if norm == 0.0:
            return vec
        return [x / norm for x in vec]


def cosine_similarity(a: list[float], b: list[float]) -> float:
    """Cosine similarity. Assumes equal length; safe for zero vectors."""
    if len(a) != len(b):
        raise ValueError("vectors must have equal length")
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (na * nb)
