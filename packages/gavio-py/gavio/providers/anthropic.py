"""AnthropicAdapter — Messages API (Claude Sonnet, Haiku, Opus)."""

from __future__ import annotations

import os
import time

from ..exceptions import ConfigurationError, ProviderUnavailableError
from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ..types import Message, TokenUsage
from ._http import post_json
from .base import ProviderAdapter

_DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
_API_VERSION = "2023-06-01"


class AnthropicAdapter(ProviderAdapter):
    """Talks to the Anthropic Messages endpoint over HTTP.

    Anthropic splits the system prompt from the message list, so any
    ``role == "system"`` messages are extracted into the ``system`` field.
    """

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        timeout_seconds: float = 30.0,
        pricing: PricingProvider | None = None,
    ) -> None:
        super().__init__(pricing)
        self._api_key = api_key or os.environ.get("ANTHROPIC_API_KEY")
        self.base_url = (base_url or _DEFAULT_BASE_URL).rstrip("/")
        self.timeout_seconds = timeout_seconds

    @property
    def provider_name(self) -> str:
        return "anthropic"

    def _headers(self) -> dict[str, str]:
        if not self._api_key:
            raise ConfigurationError(
                "ANTHROPIC_API_KEY not set (pass api_key= or set the env var)"
            )
        return {
            "x-api-key": self._api_key,
            "anthropic-version": _API_VERSION,
        }

    @staticmethod
    def _split_system(messages: list[Message]) -> tuple[str | None, list[Message]]:
        system_parts = [m["content"] for m in messages if m.get("role") == "system"]
        chat = [m for m in messages if m.get("role") != "system"]
        system = "\n".join(system_parts) if system_parts else None
        return system, chat

    async def complete(self, request: GavioRequest) -> GavioResponse:
        started = time.monotonic()
        system, chat = self._split_system(request.messages)
        payload: dict = {
            "model": request.model,
            "messages": chat,
            "max_tokens": request.max_tokens,
            "temperature": request.temperature,
        }
        if system:
            payload["system"] = system

        data = await post_json(
            f"{self.base_url}/messages",
            payload,
            self._headers(),
            timeout=self.timeout_seconds,
        )
        content = "".join(
            block.get("text", "")
            for block in data.get("content", [])
            if block.get("type") == "text"
        )
        usage_data = data.get("usage", {})
        usage = TokenUsage(
            prompt_tokens=usage_data.get("input_tokens", 0),
            completion_tokens=usage_data.get("output_tokens", 0),
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
