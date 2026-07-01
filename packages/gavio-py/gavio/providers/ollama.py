"""OllamaAdapter — local models via the Ollama chat API."""

from __future__ import annotations

import os
import time

from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ..types import TokenUsage
from ._http import post_json
from .base import ProviderAdapter

_DEFAULT_BASE_URL = "http://localhost:11434"


class OllamaAdapter(ProviderAdapter):
    """Talks to a local Ollama server (``/api/chat``). No API key; cost is $0."""

    def __init__(
        self,
        base_url: str | None = None,
        timeout_seconds: float = 60.0,
        pricing: PricingProvider | None = None,
    ) -> None:
        super().__init__(pricing)
        self.base_url = (
            base_url or os.environ.get("OLLAMA_HOST") or _DEFAULT_BASE_URL
        ).rstrip("/")
        self.timeout_seconds = timeout_seconds

    @property
    def provider_name(self) -> str:
        return "ollama"

    async def complete(self, request: GavioRequest) -> GavioResponse:
        started = time.monotonic()
        payload = {
            "model": request.model,
            "messages": request.messages,
            "stream": False,
            "options": {"temperature": request.temperature},
        }
        data = await post_json(
            f"{self.base_url}/api/chat", payload, {}, timeout=self.timeout_seconds
        )
        content = data.get("message", {}).get("content", "")
        usage = TokenUsage(
            prompt_tokens=data.get("prompt_eval_count", 0),
            completion_tokens=data.get("eval_count", 0),
        )
        return self._build_response(
            request, content, usage, data.get("model", request.model), started
        )

    async def health_check(self) -> bool:
        # Local server; assume reachable. (A real check would ping /api/tags.)
        return True
