"""GeminiAdapter — Google Generative Language API (generateContent).

``to_provider_payload`` / ``from_provider_response`` are pure functions (no network) so the
shared tool-calling test vectors can exercise them directly.

Gemini has no tool-call ids: the adapter synthesises ``call_<index>_<name>`` on the way in
and matches tool results back to calls by name on the way out. If a response does carry a
``functionCall.id`` it is kept, and echoed back on the matching ``functionResponse``.
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
    synthetic_call_id,
    tool_specs,
)
from ..types import FinishReason, Message, TokenUsage, ToolCall
from ._http import post_json
from .base import ProviderAdapter

_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

_FINISH_REASONS = {
    "STOP": FinishReason.STOP,
    "MAX_TOKENS": FinishReason.LENGTH,
    "SAFETY": FinishReason.CONTENT_FILTER,
    "RECITATION": FinishReason.CONTENT_FILTER,
    "BLOCKLIST": FinishReason.CONTENT_FILTER,
    "PROHIBITED_CONTENT": FinishReason.CONTENT_FILTER,
    "SPII": FinishReason.CONTENT_FILTER,
}

_CALLING_MODES = {"auto": "AUTO", "none": "NONE", "required": "ANY", "tool": "ANY"}


def to_provider_payload(request: GavioRequest) -> dict[str, Any]:
    """Build the ``generateContent`` request body for ``request``."""
    system, contents = _to_contents(request.messages)
    payload: dict[str, Any] = {
        "contents": contents,
        "generationConfig": {
            "temperature": request.temperature,
            "maxOutputTokens": request.max_tokens,
        },
    }
    if system:
        payload["systemInstruction"] = {"parts": [{"text": system}]}
    specs = tool_specs(request.tools)
    if specs:
        payload["tools"] = [{"functionDeclarations": [_to_native_tool(spec) for spec in specs]}]
        choice = active_tool_choice(request.tools, request.tool_choice)
        if choice is not None:
            payload["toolConfig"] = {"functionCallingConfig": _to_native_choice(choice)}
    return payload


def from_provider_response(payload: dict[str, Any]) -> ProviderCompletion:
    """Parse a ``generateContent`` response body into normalised parts."""
    candidate = (payload.get("candidates") or [{}])[0]
    parts = (candidate.get("content") or {}).get("parts") or []
    content = "".join(p.get("text", "") for p in parts)
    tool_calls: list[ToolCall] = []
    for part in parts:
        call = part.get("functionCall")
        if call is not None:
            tool_calls.append(_from_native_call(len(tool_calls), call))
    usage = payload.get("usageMetadata") or {}
    return ProviderCompletion(
        content=content,
        tool_calls=tool_calls,
        finish_reason=normalise_finish_reason(
            candidate.get("finishReason"), _FINISH_REASONS, tool_calls
        ),
        usage=TokenUsage(
            prompt_tokens=usage.get("promptTokenCount", 0),
            completion_tokens=usage.get("candidatesTokenCount", 0),
        ),
    )


def _to_contents(messages: list[Message]) -> tuple[str | None, list[dict[str, Any]]]:
    system: str | None = None
    contents: list[dict[str, Any]] = []
    # call id -> (tool name, whether the provider supplied the id)
    calls_by_id: dict[str, tuple[str, bool]] = {}
    for message in messages:
        role = message.get("role")
        text = message.get("content") or ""
        if role == "system":
            system = f"{system}\n{text}" if system else text
            continue
        calls = message_tool_calls(message)
        call_id = message_tool_call_id(message)
        if role == "assistant" and calls:
            parts: list[dict[str, Any]] = [{"text": text}] if text else []
            for index, call in enumerate(calls):
                supplied = call.id != synthetic_call_id(index, call.name)
                calls_by_id[call.id] = (call.name, supplied)
                native: dict[str, Any] = {"name": call.name, "args": call.arguments}
                if supplied:
                    native["id"] = call.id
                parts.append({"functionCall": native})
            contents.append({"role": "model", "parts": parts})
        elif role == "tool" and call_id:
            name, supplied = calls_by_id.get(call_id, (message.get("name"), False))
            if not name:
                raise ConfigurationError(
                    f"tool result {call_id!r} matches no earlier tool call and has no name"
                )
            response = {"error" if message_is_error(message) else "output": text}
            result: dict[str, Any] = {"name": name, "response": response}
            if supplied:
                result["id"] = call_id
            part = {"functionResponse": result}
            if _is_tool_result_turn(contents[-1] if contents else None):
                contents[-1]["parts"].append(part)
            else:
                contents.append({"role": "user", "parts": [part]})
        else:
            g_role = "model" if role == "assistant" else "user"
            contents.append({"role": g_role, "parts": [{"text": text}]})
    return system, contents


def _is_tool_result_turn(content: dict[str, Any] | None) -> bool:
    return (
        content is not None
        and content["role"] == "user"
        and all("functionResponse" in part for part in content["parts"])
    )


def _to_native_tool(spec: ToolSpec) -> dict[str, Any]:
    declaration: dict[str, Any] = {"name": spec.name}
    if spec.description:
        declaration["description"] = spec.description
    # Gemini rejects an object schema without properties, so a no-argument tool omits it.
    if spec.input_schema and spec.input_schema.get("properties"):
        declaration["parameters"] = spec.input_schema
    return declaration


def _to_native_choice(choice: ToolChoice) -> dict[str, Any]:
    config: dict[str, Any] = {"mode": _CALLING_MODES[choice.mode]}
    if choice.mode == "tool":
        config["allowedFunctionNames"] = [choice.name]
    return config


def _from_native_call(index: int, call: dict[str, Any]) -> ToolCall:
    name = call.get("name")
    if not name:
        raise MalformedToolCallError(f"functionCall at index {index} has no name")
    return ToolCall(
        id=call.get("id") or synthetic_call_id(index, name),
        name=name,
        arguments=parse_arguments(call.get("args"), name),
    )


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
        return _to_contents(messages)

    def _payload(self, request: GavioRequest) -> dict:
        return to_provider_payload(request)

    async def complete(self, request: GavioRequest) -> GavioResponse:
        if not self._api_key:
            raise ConfigurationError("GEMINI_API_KEY not set")
        started = time.monotonic()
        url = (
            f"{self.base_url}/models/{request.model}:generateContent"
            f"?key={self._api_key}"
        )
        data = await post_json(
            url, to_provider_payload(request), {}, timeout=self.timeout_seconds
        )
        return self._build_completion_response(request, from_provider_response(data), started)

    async def health_check(self) -> bool:
        try:
            return bool(self._api_key)
        except ProviderUnavailableError:
            return False
