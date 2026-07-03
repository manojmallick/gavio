"""ProviderAdapter ABC and shared response-building helpers."""

from __future__ import annotations

import time
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator

from ..pricing import PricingProvider, estimate_tokens
from ..request import GavioRequest
from ..response import GavioResponse
from ..types import TokenUsage


class ProviderAdapter(ABC):
    """Adapter to one LLM provider. All adapters share a pricing provider."""

    def __init__(self, pricing: PricingProvider | None = None) -> None:
        self._pricing = pricing or PricingProvider()

    @property
    @abstractmethod
    def provider_name(self) -> str: ...

    @abstractmethod
    async def complete(self, request: GavioRequest) -> GavioResponse: ...

    async def stream(self, request: GavioRequest) -> AsyncIterator[str]:
        raise NotImplementedError(f"{self.provider_name} does not support streaming")
        yield ""  # pragma: no cover - makes this an async generator

    async def embed(self, request: GavioRequest) -> GavioResponse:
        """Embed the request's message contents (F-SEC-10). Optional per adapter."""
        from ..exceptions import ProviderError

        raise ProviderError(f"{self.provider_name} does not support embeddings")

    @abstractmethod
    async def health_check(self) -> bool: ...

    @property
    def reported_model_version(self) -> str | None:
        return None

    def build_stream_response(
        self, request: GavioRequest, content: str, started_at: float
    ) -> GavioResponse:
        """Build a response from a fully buffered stream (F-REL-06).

        Streamed chunks carry text only, so token usage is estimated from the
        assembled prompt and content.
        """
        usage = TokenUsage(
            prompt_tokens=estimate_tokens(request.prompt_text()),
            completion_tokens=estimate_tokens(content),
        )
        model_version = self.reported_model_version or request.model
        return self._build_response(request, content, usage, model_version, started_at)

    def _build_embed_response(
        self,
        request: GavioRequest,
        vectors: list[list[float]],
        usage: TokenUsage,
        model_version: str,
        started_at: float,
    ) -> GavioResponse:
        """Build an embedding response — empty content, one vector per input."""
        response = self._build_response(request, "", usage, model_version, started_at)
        response.embeddings = vectors
        return response

    def _build_response(
        self,
        request: GavioRequest,
        content: str,
        usage: TokenUsage,
        model_version: str,
        started_at: float,
    ) -> GavioResponse:
        latency_ms = int((time.monotonic() - started_at) * 1000)
        return GavioResponse(
            trace_id=request.trace_id,
            content=content,
            model=request.model,
            provider=self.provider_name,
            model_version=model_version or request.model,
            usage=usage,
            cost_usd=self._pricing.estimate(request.model, usage),
            latency_ms=latency_ms,
        )
