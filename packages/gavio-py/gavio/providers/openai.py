"""OpenAIAdapter — Chat Completions API (GPT-4o, o1, ...)."""

from __future__ import annotations

import os
import time

from ..exceptions import ConfigurationError, ProviderUnavailableError
from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ..types import TokenUsage
from ._http import post_json
from .base import ProviderAdapter

_DEFAULT_BASE_URL = "https://api.openai.com/v1"


class OpenAIAdapter(ProviderAdapter):
    """Talks to the OpenAI Chat Completions endpoint over HTTP."""

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        timeout_seconds: float = 30.0,
        organization: str | None = None,
        pricing: PricingProvider | None = None,
    ) -> None:
        super().__init__(pricing)
        self._api_key = api_key or os.environ.get("OPENAI_API_KEY")
        self.base_url = (base_url or _DEFAULT_BASE_URL).rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.organization = organization

    @property
    def provider_name(self) -> str:
        return "openai"

    def _headers(self) -> dict[str, str]:
        if not self._api_key:
            raise ConfigurationError(
                "OPENAI_API_KEY not set (pass api_key= or set the env var)"
            )
        headers = {"Authorization": f"Bearer {self._api_key}"}
        if self.organization:
            headers["OpenAI-Organization"] = self.organization
        return headers

    async def complete(self, request: GavioRequest) -> GavioResponse:
        started = time.monotonic()
        payload = {
            "model": request.model,
            "messages": request.messages,
            "temperature": request.temperature,
            "max_tokens": request.max_tokens,
        }
        data = await post_json(
            f"{self.base_url}/chat/completions",
            payload,
            self._headers(),
            timeout=self.timeout_seconds,
        )
        choice = data["choices"][0]
        content = choice["message"]["content"] or ""
        usage_data = data.get("usage", {})
        usage = TokenUsage(
            prompt_tokens=usage_data.get("prompt_tokens", 0),
            completion_tokens=usage_data.get("completion_tokens", 0),
        )
        return self._build_response(
            request, content, usage, data.get("model", request.model), started
        )

    async def health_check(self) -> bool:
        try:
            self._headers()
            return True
        except ConfigurationError:
            return False
        except ProviderUnavailableError:
            return False
