"""OpenAI drop-in shim (F-DX-04) — point existing OpenAI SDK code at Gavio.

    from gavio import Gateway
    from gavio.shim.openai import GavioOpenAI

    client = GavioOpenAI(Gateway.builder().provider("openai").model("gpt-4o").build())
    resp = client.chat.completions.create(
        model="gpt-4o", messages=[{"role": "user", "content": "hi"}]
    )
    print(resp.choices[0].message.content)

The response object mirrors the shape of the OpenAI SDK's ChatCompletion, so
most existing call sites work unchanged — now with PII guard, audit, caching,
etc. running underneath.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..gateway import Gateway
from ..response import GavioResponse


@dataclass
class _Message:
    role: str
    content: str


@dataclass
class _Choice:
    index: int
    message: _Message
    finish_reason: str = "stop"


@dataclass
class _Usage:
    prompt_tokens: int
    completion_tokens: int
    total_tokens: int


@dataclass
class ChatCompletion:
    id: str
    model: str
    choices: list[_Choice]
    usage: _Usage
    object: str = "chat.completion"
    gavio: dict[str, Any] = field(default_factory=dict)


def _to_completion(resp: GavioResponse) -> ChatCompletion:
    return ChatCompletion(
        id=resp.trace_id,
        model=resp.model_version or resp.model,
        choices=[_Choice(0, _Message("assistant", resp.content))],
        usage=_Usage(
            prompt_tokens=resp.usage.prompt_tokens,
            completion_tokens=resp.usage.completion_tokens,
            total_tokens=resp.usage.total_tokens,
        ),
        gavio={
            "cost_usd": resp.cost_usd,
            "cache_hit": resp.cache_hit,
            "interceptors_fired": resp.interceptors_fired,
        },
    )


class _Completions:
    def __init__(self, gateway: Gateway) -> None:
        self._gw = gateway

    def create(
        self,
        messages: list[dict],
        model: str | None = None,
        temperature: float = 0.7,
        max_tokens: int = 1024,
        **kwargs: Any,
    ) -> ChatCompletion:
        resp = self._gw.complete_sync(
            messages=messages,
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
        )
        return _to_completion(resp)

    async def acreate(
        self,
        messages: list[dict],
        model: str | None = None,
        temperature: float = 0.7,
        max_tokens: int = 1024,
        **kwargs: Any,
    ) -> ChatCompletion:
        resp = await self._gw.complete(
            messages=messages,
            model=model,
            temperature=temperature,
            max_tokens=max_tokens,
        )
        return _to_completion(resp)


class _Chat:
    def __init__(self, gateway: Gateway) -> None:
        self.completions = _Completions(gateway)


class GavioOpenAI:
    """OpenAI-client-shaped facade over a Gavio :class:`Gateway`."""

    def __init__(self, gateway: Gateway) -> None:
        self.chat = _Chat(gateway)
