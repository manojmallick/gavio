"""OpenAIAdapter — Chat Completions API (GPT-4o, o1, ...).

``to_provider_payload`` / ``from_provider_response`` are pure functions (no network) so the
shared tool-calling test vectors can exercise them directly. Azure OpenAI and OpenRouter
speak the same Chat Completions shape and reuse them.
"""

from __future__ import annotations

import os
import time
from typing import Any

from ..exceptions import ConfigurationError, MalformedToolCallError, ProviderUnavailableError
from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ..tool_calling import (
    ProviderCompletion,
    ToolChoice,
    ToolSpec,
    active_tool_choice,
    dump_arguments,
    message_tool_call_id,
    message_tool_calls,
    normalise_finish_reason,
    parse_arguments,
    synthetic_call_id,
    tool_specs,
)
from ..types import FinishReason, Message, TokenUsage, ToolCall
from ._http import post_json
from .base import ProviderAdapter

_DEFAULT_BASE_URL = "https://api.openai.com/v1"

_FINISH_REASONS = {
    "stop": FinishReason.STOP,
    "length": FinishReason.LENGTH,
    "tool_calls": FinishReason.TOOL_CALLS,
    "content_filter": FinishReason.CONTENT_FILTER,
}


def to_provider_payload(request: GavioRequest, *, include_model: bool = True) -> dict[str, Any]:
    """Build the Chat Completions request body for ``request``.

    ``include_model=False`` omits ``model`` for Azure, which routes by deployment.
    """
    payload: dict[str, Any] = {}
    if include_model:
        payload["model"] = request.model
    payload["messages"] = [_to_native_message(message) for message in request.messages]
    payload["temperature"] = request.temperature
    payload["max_tokens"] = request.max_tokens
    specs = tool_specs(request.tools)
    if specs:
        payload["tools"] = [_to_native_tool(spec) for spec in specs]
        choice = active_tool_choice(request.tools, request.tool_choice)
        if choice is not None:
            payload["tool_choice"] = _to_native_choice(choice)
    return payload


def from_provider_response(payload: dict[str, Any]) -> ProviderCompletion:
    """Parse a Chat Completions response body into normalised parts."""
    choice = payload["choices"][0]
    message = choice.get("message") or {}
    tool_calls = [
        _from_native_call(index, raw) for index, raw in enumerate(message.get("tool_calls") or [])
    ]
    usage = payload.get("usage") or {}
    return ProviderCompletion(
        content=message.get("content") or "",
        tool_calls=tool_calls,
        finish_reason=normalise_finish_reason(
            choice.get("finish_reason"), _FINISH_REASONS, tool_calls
        ),
        usage=TokenUsage(
            prompt_tokens=usage.get("prompt_tokens", 0),
            completion_tokens=usage.get("completion_tokens", 0),
        ),
        model_version=payload.get("model"),
    )


def _to_native_message(message: Message) -> dict[str, Any]:
    calls = message_tool_calls(message)
    if message.get("role") == "assistant" and calls:
        return {
            "role": "assistant",
            "content": message.get("content") or None,
            "tool_calls": [
                {
                    "id": call.id,
                    "type": "function",
                    "function": {"name": call.name, "arguments": dump_arguments(call.arguments)},
                }
                for call in calls
            ],
        }
    call_id = message_tool_call_id(message)
    if message.get("role") == "tool" and call_id:
        return {"role": "tool", "tool_call_id": call_id, "content": message.get("content") or ""}
    return dict(message)


def _to_native_tool(spec: ToolSpec) -> dict[str, Any]:
    function: dict[str, Any] = {"name": spec.name}
    if spec.description:
        function["description"] = spec.description
    function["parameters"] = spec.input_schema or {"type": "object", "properties": {}}
    return {"type": "function", "function": function}


def _to_native_choice(choice: ToolChoice) -> str | dict[str, Any]:
    if choice.mode == "tool":
        return {"type": "function", "function": {"name": choice.name}}
    return choice.mode


def _from_native_call(index: int, raw: dict[str, Any]) -> ToolCall:
    function = raw.get("function") or {}
    name = function.get("name")
    if not name:
        raise MalformedToolCallError(f"tool call at index {index} has no function name")
    return ToolCall(
        id=raw.get("id") or synthetic_call_id(index, name),
        name=name,
        arguments=parse_arguments(function.get("arguments"), name),
    )


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
        data = await post_json(
            f"{self.base_url}/chat/completions",
            to_provider_payload(request),
            self._headers(),
            timeout=self.timeout_seconds,
        )
        return self._build_completion_response(request, from_provider_response(data), started)

    async def health_check(self) -> bool:
        try:
            self._headers()
            return True
        except ConfigurationError:
            return False
        except ProviderUnavailableError:
            return False
