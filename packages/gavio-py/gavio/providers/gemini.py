"""GeminiAdapter — Google Generative Language API (generateContent)."""

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

_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"


class GeminiAdapter(ProviderAdapter):
    """Talks to the Gemini generateContent endpoint over HTTP.

    Gemini uses roles ``user``/``model`` (not ``assistant``) and a separate
    ``systemInstruction`` field, so messages are mapped accordingly.
    """

    def __init__(
        self,
        api_key: str | None = None,
        base_url: str | None = None,
        timeout_seconds: float = 30.0,
        pricing: PricingProvider | None = None,
    ) -> None:
        super().__init__(pricing)
        self._api_key = (
            api_key
            or os.environ.get("GEMINI_API_KEY")
            or os.environ.get("GOOGLE_API_KEY")
        )
        self.base_url = (base_url or _DEFAULT_BASE_URL).rstrip("/")
        self.timeout_seconds = timeout_seconds

    @property
    def provider_name(self) -> str:
        return "gemini"

    @staticmethod
    def _to_contents(
        messages: list[Message],
    ) -> tuple[str | None, list[dict]]:
        system: str | None = None
        contents: list[dict] = []
        for m in messages:
            role = m.get("role")
            text = m.get("content", "")
            if role == "system":
                system = f"{system}\n{text}" if system else text
                continue
            g_role = "model" if role == "assistant" else "user"
            contents.append({"role": g_role, "parts": [{"text": text}]})
        return system, contents

    def _payload(self, request: GavioRequest) -> dict:
        system, contents = self._to_contents(request.messages)
        payload: dict = {
            "contents": contents,
            "generationConfig": {
                "temperature": request.temperature,
                "maxOutputTokens": request.max_tokens,
            },
        }
        if system:
            payload["systemInstruction"] = {"parts": [{"text": system}]}
        return payload

    async def complete(self, request: GavioRequest) -> GavioResponse:
        if not self._api_key:
            raise ConfigurationError("GEMINI_API_KEY not set")
        started = time.monotonic()
        url = (
            f"{self.base_url}/models/{request.model}:generateContent"
            f"?key={self._api_key}"
        )
        data = await post_json(
            url, self._payload(request), {}, timeout=self.timeout_seconds
        )
        candidates = data.get("candidates") or [{}]
        parts = candidates[0].get("content", {}).get("parts", [])
        content = "".join(p.get("text", "") for p in parts)
        um = data.get("usageMetadata", {})
        usage = TokenUsage(
            prompt_tokens=um.get("promptTokenCount", 0),
            completion_tokens=um.get("candidatesTokenCount", 0),
        )
        return self._build_response(request, content, usage, request.model, started)

    async def health_check(self) -> bool:
        try:
            return bool(self._api_key)
        except ProviderUnavailableError:
            return False
