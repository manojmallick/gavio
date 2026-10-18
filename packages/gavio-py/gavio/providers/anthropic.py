"""AnthropicAdapter — Messages API (Claude Sonnet, Haiku, Opus).

``to_provider_payload`` / ``from_provider_response`` are pure functions (no network) so the
shared tool-calling test vectors can exercise them directly.
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
    message_is_error,
    message_tool_call_id,
    message_tool_calls,
    normalise_finish_reason,
    parse_arguments,
    tool_specs,
)
from ..types import FinishReason, Message, TokenUsage, ToolCall
from ._http import post_json
from .base import ProviderAdapter

_DEFAULT_BASE_URL = "https://api.anthropic.com/v1"
_API_VERSION = "2023-06-01"

_FINISH_REASONS = {
    "end_turn": FinishReason.STOP,
    "stop_sequence": FinishReason.STOP,
    "max_tokens": FinishReason.LENGTH,
    "tool_use": FinishReason.TOOL_CALLS,
    "refusal": FinishReason.CONTENT_FILTER,
}


def to_provider_payload(request: GavioRequest) -> dict[str, Any]:
    """Build the Messages API request body for ``request``.

    System messages move to the ``system`` field. An assistant turn with tool calls becomes
    ``tool_use`` blocks; consecutive tool-role messages become one user turn of
    ``tool_result`` blocks, as the API requires for parallel calls.
    """
    system, chat = _split_system(request.messages)
    payload: dict[str, Any] = {
        "model": request.model,
        "messages": _to_native_messages(chat),
        "max_tokens": request.max_tokens,
        "temperature": request.temperature,
    }
    if system:
        payload["system"] = system
    specs = tool_specs(request.tools)
    if specs:
        payload["tools"] = [_to_native_tool(spec) for spec in specs]
        choice = active_tool_choice(request.tools, request.tool_choice)
        if choice is not None:
            payload["tool_choice"] = _to_native_choice(choice)
    return payload


def from_provider_response(payload: dict[str, Any]) -> ProviderCompletion:
    """Parse a Messages API response body into normalised parts."""
    blocks = payload.get("content") or []
    content = "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
    tool_calls = [_from_native_block(b) for b in blocks if b.get("type") == "tool_use"]
    usage = payload.get("usage") or {}
    return ProviderCompletion(
        content=content,
        tool_calls=tool_calls,
        finish_reason=normalise_finish_reason(
            payload.get("stop_reason"), _FINISH_REASONS, tool_calls
        ),
        usage=TokenUsage(
            prompt_tokens=usage.get("input_tokens", 0),
            completion_tokens=usage.get("output_tokens", 0),
        ),
        model_version=payload.get("model"),
    )


def _split_system(messages: list[Message]) -> tuple[str | None, list[Message]]:
    system_parts = [m.get("content") or "" for m in messages if m.get("role") == "system"]
    chat = [m for m in messages if m.get("role") != "system"]
    system = "\n".join(system_parts) if system_parts else None
    return system, chat


def _to_native_messages(messages: list[Message]) -> list[dict[str, Any]]:
    native: list[dict[str, Any]] = []
    for message in messages:
        calls = message_tool_calls(message)
        call_id = message_tool_call_id(message)
        if message.get("role") == "assistant" and calls:
            text = message.get("content") or ""
            blocks: list[dict[str, Any]] = [{"type": "text", "text": text}] if text else []
            blocks.extend(
                {"type": "tool_use", "id": call.id, "name": call.name, "input": call.arguments}
                for call in calls
            )
            native.append({"role": "assistant", "content": blocks})
        elif message.get("role") == "tool" and call_id:
            block: dict[str, Any] = {
                "type": "tool_result",
                "tool_use_id": call_id,
                "content": message.get("content") or "",
            }
            if message_is_error(message):
                block["is_error"] = True
            if _is_tool_result_turn(native[-1] if native else None):
                native[-1]["content"].append(block)
            else:
                native.append({"role": "user", "content": [block]})
        else:
            native.append(dict(message))
    return native


def _is_tool_result_turn(message: dict[str, Any] | None) -> bool:
    if message is None or message.get("role") != "user":
        return False
    content = message.get("content")
    return isinstance(content, list) and all(b.get("type") == "tool_result" for b in content)


def _to_native_tool(spec: ToolSpec) -> dict[str, Any]:
    tool: dict[str, Any] = {"name": spec.name}
    if spec.description:
        tool["description"] = spec.description
    tool["input_schema"] = spec.input_schema or {"type": "object", "properties": {}}
    return tool


def _to_native_choice(choice: ToolChoice) -> dict[str, Any]:
    if choice.mode == "tool":
        return {"type": "tool", "name": choice.name}
    return {"type": "any" if choice.mode == "required" else choice.mode}


def _from_native_block(block: dict[str, Any]) -> ToolCall:
    name = block.get("name")
    if not name or not block.get("id"):
        raise MalformedToolCallError("tool_use block is missing its id or name")
    return ToolCall(
        id=block["id"], name=name, arguments=parse_arguments(block.get("input"), name)
    )


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
        return _split_system(messages)

    async def complete(self, request: GavioRequest) -> GavioResponse:
        started = time.monotonic()
        data = await post_json(
            f"{self.base_url}/messages",
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
