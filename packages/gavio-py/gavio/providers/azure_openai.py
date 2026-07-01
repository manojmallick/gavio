"""AzureOpenAIAdapter — Azure OpenAI deployment-based chat completions."""

from __future__ import annotations

import os
import time

from ..exceptions import ConfigurationError
from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ..types import TokenUsage
from ._http import post_json
from .base import ProviderAdapter

_DEFAULT_API_VERSION = "2024-06-01"


class AzureOpenAIAdapter(ProviderAdapter):
    """Azure OpenAI: routes to a named deployment on your resource endpoint.

    The request body matches OpenAI Chat Completions; auth uses the ``api-key``
    header. ``deployment`` defaults to the request model if not set.
    """

    def __init__(
        self,
        api_key: str | None = None,
        endpoint: str | None = None,
        deployment: str | None = None,
        api_version: str = _DEFAULT_API_VERSION,
        timeout_seconds: float = 30.0,
        pricing: PricingProvider | None = None,
    ) -> None:
        super().__init__(pricing)
        self._api_key = api_key or os.environ.get("AZURE_OPENAI_API_KEY")
        self.endpoint = (endpoint or os.environ.get("AZURE_OPENAI_ENDPOINT") or "").rstrip("/")
        self.deployment = deployment or os.environ.get("AZURE_OPENAI_DEPLOYMENT")
        self.api_version = api_version
        self.timeout_seconds = timeout_seconds

    @property
    def provider_name(self) -> str:
        return "azure_openai"

    def _url(self, request: GavioRequest) -> str:
        deployment = self.deployment or request.model
        return (
            f"{self.endpoint}/openai/deployments/{deployment}/chat/completions"
            f"?api-version={self.api_version}"
        )

    async def complete(self, request: GavioRequest) -> GavioResponse:
        if not self._api_key or not self.endpoint:
            raise ConfigurationError(
                "AZURE_OPENAI_API_KEY and AZURE_OPENAI_ENDPOINT must be set"
            )
        started = time.monotonic()
        payload = {
            "messages": request.messages,
            "temperature": request.temperature,
            "max_tokens": request.max_tokens,
        }
        data = await post_json(
            self._url(request),
            payload,
            {"api-key": self._api_key},
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
        return bool(self._api_key and self.endpoint)
