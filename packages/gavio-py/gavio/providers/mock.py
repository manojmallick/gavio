"""MockProvider — deterministic, offline provider for dev mode and tests."""

from __future__ import annotations

import hashlib
import time
from collections.abc import AsyncIterator

from ..pricing import PricingProvider, estimate_tokens
from ..request import GavioRequest
from ..response import GavioResponse
from ..types import TokenUsage
from .base import ProviderAdapter


class MockProvider(ProviderAdapter):
    """Returns a canned response without any network call.

    If ``response`` is None, it echoes the last user message so the pipeline
    (including PII restore) is observable end to end.
    """

    def __init__(
        self,
        response: str | None = None,
        model_version: str = "mock-1",
        pricing: PricingProvider | None = None,
    ) -> None:
        super().__init__(pricing)
        self._response = response
        self._model_version = model_version

    @property
    def provider_name(self) -> str:
        return "mock"

    def _content_for(self, request: GavioRequest) -> str:
        if self._response is not None:
            return self._response
        last_user = next(
            (m.get("content", "") for m in reversed(request.messages) if m.get("role") == "user"),
            "",
        )
        return f"[mock reply] {last_user}"

    async def complete(self, request: GavioRequest) -> GavioResponse:
        started = time.monotonic()
        content = self._content_for(request)
        usage = TokenUsage(
            prompt_tokens=estimate_tokens(request.prompt_text()),
            completion_tokens=estimate_tokens(content),
        )
        return self._build_response(request, content, usage, self._model_version, started)

    async def embed(self, request: GavioRequest) -> GavioResponse:
        """Deterministic 8-dim vector per message content (F-SEC-10)."""
        started = time.monotonic()
        vectors = [_mock_vector(m.get("content", "")) for m in request.messages]
        usage = TokenUsage(prompt_tokens=estimate_tokens(request.prompt_text()))
        return self._build_embed_response(request, vectors, usage, self._model_version, started)

    async def stream(self, request: GavioRequest) -> AsyncIterator[str]:
        for token in self._content_for(request).split(" "):
            yield token + " "

    async def health_check(self) -> bool:
        return True

    @property
    def reported_model_version(self) -> str | None:
        return self._model_version


def _mock_vector(text: str, dims: int = 8) -> list[float]:
    """Stable pseudo-embedding: sha256 bytes scaled to [0, 1)."""
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    return [digest[i] / 255.0 for i in range(dims)]
