"""OllamaAdapter — local models via the Ollama chat API.

``to_provider_payload`` / ``from_provider_response`` are pure functions (no network).

Tool calling follows the documented ``/api/chat`` shape (docs/api.md in ollama/ollama): tools
are ``{"type": "function", "function": {...}}`` declarations, tool calls carry an object
``arguments`` and no id, and a tool result is a ``role: "tool"`` message naming the tool in
``tool_name``. Ollama has no ``tool_choice`` parameter, so anything but ``auto`` is rejected.
The shape is taken from that documentation and the shared vectors; it has not been
exercised against a live Ollama server.
"""

from __future__ import annotations

import os
import time
from typing import Any

from ..exceptions import ConfigurationError, MalformedToolCallError
from ..pricing import PricingProvider
from ..request import GavioRequest
from ..response import GavioResponse
from ..tool_calling import (
    ProviderCompletion,
    ToolSpec,
    active_tool_choice,
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

_DEFAULT_BASE_URL = "http://localhost:11434"

_FINISH_REASONS = {"stop": FinishReason.STOP, "length": FinishReason.LENGTH}


def to_provider_payload(request: GavioRequest) -> dict[str, Any]:
    """Build the ``/api/chat`` request body for ``request``."""
    payload: dict[str, Any] = {
        "model": request.model,
        "messages": _to_native_messages(request.messages),
        "stream": False,
        "options": {"temperature": request.temperature},
    }
    specs = tool_specs(request.tools)
    if specs:
        choice = active_tool_choice(request.tools, request.tool_choice)
        if choice is not None and choice.mode != "auto":
            raise ConfigurationError(
                "ollama's /api/chat has no tool_choice parameter; only 'auto' is supported"
            )
        payload["tools"] = [_to_native_tool(spec) for spec in specs]
    return payload


def from_provider_response(payload: dict[str, Any]) -> ProviderCompletion:
    """Parse an ``/api/chat`` response body into normalised parts."""
    message = payload.get("message") or {}
    tool_calls = [
        _from_native_call(index, raw) for index, raw in enumerate(message.get("tool_calls") or [])
    ]
    return ProviderCompletion(
        content=message.get("content") or "",
        tool_calls=tool_calls,
        finish_reason=normalise_finish_reason(
            payload.get("done_reason"), _FINISH_REASONS, tool_calls
        ),
        usage=TokenUsage(
            prompt_tokens=payload.get("prompt_eval_count", 0),
            completion_tokens=payload.get("eval_count", 0),
        ),
        model_version=payload.get("model"),
    )


def _to_native_messages(messages: list[Message]) -> list[dict[str, Any]]:
    native: list[dict[str, Any]] = []
    names_by_id: dict[str, str] = {}
    for message in messages:
        calls = message_tool_calls(message)
        call_id = message_tool_call_id(message)
        if message.get("role") == "assistant" and calls:
            names_by_id.update({call.id: call.name for call in calls})
            native.append(
                {
                    "role": "assistant",
                    "content": message.get("content") or "",
                    "tool_calls": [
                        {"function": {"name": call.name, "arguments": call.arguments}}
                        for call in calls
                    ],
                }
            )
        elif message.get("role") == "tool" and call_id:
            result: dict[str, Any] = {"role": "tool", "content": message.get("content") or ""}
            name = names_by_id.get(call_id) or message.get("name")
            if name:
                result["tool_name"] = name
            native.append(result)
        else:
            native.append(dict(message))
    return native


def _to_native_tool(spec: ToolSpec) -> dict[str, Any]:
    function: dict[str, Any] = {"name": spec.name}
    if spec.description:
        function["description"] = spec.description
    function["parameters"] = spec.input_schema or {"type": "object", "properties": {}}
    return {"type": "function", "function": function}


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
        data = await post_json(
            f"{self.base_url}/api/chat",
            to_provider_payload(request),
            {},
            timeout=self.timeout_seconds,
        )
        return self._build_completion_response(request, from_provider_response(data), started)

    async def health_check(self) -> bool:
        # Local server; assume reachable. (A real check would ping /api/tags.)
        return True
