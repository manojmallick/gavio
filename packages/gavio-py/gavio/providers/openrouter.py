"""OpenRouterAdapter — OpenAI-compatible Chat Completions API."""

from __future__ import annotations

import os
import time

from ..exceptions import ConfigurationError, ProviderUnavailableError
from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ._http import post_json
from .base import ProviderAdapter
from .openai import from_provider_response, to_provider_payload

__all__ = ["OpenRouterAdapter", "from_provider_response", "to_provider_payload"]

_DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"


class OpenRouterAdapter(ProviderAdapter):
    """Talks to OpenRouter's OpenAI-compatible Chat Completions endpoint."""

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        timeout_seconds: float = 30.0,
        http_referer: str | None = None,
        app_title: str | None = None,
        pricing: PricingProvider | None = None,
    ) -> None:
        super().__init__(pricing)
        self._api_key = api_key or os.environ.get("OPENROUTER_API_KEY")
        self.base_url = (
            base_url or os.environ.get("OPENROUTER_BASE_URL") or _DEFAULT_BASE_URL
        ).rstrip("/")
        self.timeout_seconds = timeout_seconds
        self.http_referer = (
            http_referer
            or os.environ.get("OPENROUTER_HTTP_REFERER")
            or os.environ.get("OPENROUTER_REFERER")
        )
        self.app_title = (
            app_title
            or os.environ.get("OPENROUTER_APP_TITLE")
            or os.environ.get("OPENROUTER_TITLE")
        )

    @property
    def provider_name(self) -> str:
        return "openrouter"

    def url(self) -> str:
        return f"{self.base_url}/chat/completions"

    def headers(self) -> dict[str, str]:
        if not self._api_key:
            raise ConfigurationError(
                "OPENROUTER_API_KEY not set (pass api_key= or set the env var)"
            )
        headers = {"Authorization": f"Bearer {self._api_key}"}
        if self.http_referer:
            headers["HTTP-Referer"] = self.http_referer
        if self.app_title:
            headers["X-OpenRouter-Title"] = self.app_title
        return headers

    async def complete(self, request: GavioRequest) -> GavioResponse:
        started = time.monotonic()
        data = await post_json(
            self.url(),
            to_provider_payload(request),
            self.headers(),
            timeout=self.timeout_seconds,
        )
        return self._build_completion_response(request, from_provider_response(data), started)

    async def health_check(self) -> bool:
        try:
            self.headers()
            return True
        except ConfigurationError:
            return False
        except ProviderUnavailableError:
            return False
