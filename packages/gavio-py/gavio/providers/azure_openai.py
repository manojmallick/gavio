"""AzureOpenAIAdapter — Azure OpenAI deployment-based chat completions."""

from __future__ import annotations

import os
import time
from typing import Any

from ..exceptions import ConfigurationError
from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ._http import post_json
from .base import ProviderAdapter
from .openai import from_provider_response
from .openai import to_provider_payload as _openai_payload

__all__ = ["AzureOpenAIAdapter", "from_provider_response", "to_provider_payload"]

_DEFAULT_API_VERSION = "2024-06-01"


def to_provider_payload(request: GavioRequest) -> dict[str, Any]:
    """Chat Completions body without ``model`` — Azure routes by deployment."""
    return _openai_payload(request, include_model=False)


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
        data = await post_json(
            self._url(request),
            to_provider_payload(request),
            {"api-key": self._api_key},
            timeout=self.timeout_seconds,
        )
        return self._build_completion_response(request, from_provider_response(data), started)

    async def health_check(self) -> bool:
        return bool(self._api_key and self.endpoint)
