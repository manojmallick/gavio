"""Unit tests for v0.2.0 providers (Gemini, Azure OpenAI, Ollama) — no network."""

from __future__ import annotations

from gavio.providers import build_adapter
from gavio.providers.azure_openai import AzureOpenAIAdapter
from gavio.providers.gemini import GeminiAdapter
from gavio.providers.ollama import OllamaAdapter
from gavio.request import GavioRequest
from gavio.types import Provider


def _req(model, provider, messages):
    return GavioRequest(messages=messages, model=model, provider=provider)


def test_registry_resolves_new_providers():
    assert isinstance(build_adapter(Provider.GEMINI), GeminiAdapter)
    assert isinstance(build_adapter(Provider.AZURE_OPENAI), AzureOpenAIAdapter)
    assert isinstance(build_adapter(Provider.OLLAMA), OllamaAdapter)


def test_gemini_role_mapping_and_system():
    system, contents = GeminiAdapter._to_contents(
        [
            {"role": "system", "content": "be terse"},
            {"role": "user", "content": "hi"},
            {"role": "assistant", "content": "hello"},
        ]
    )
    assert system == "be terse"
    assert contents[0]["role"] == "user"
    assert contents[1]["role"] == "model"  # assistant -> model
    assert contents[1]["parts"][0]["text"] == "hello"


def test_gemini_payload_shape():
    adapter = GeminiAdapter(api_key="k")
    payload = adapter._payload(
        _req("gemini-2.0-flash", Provider.GEMINI, [{"role": "user", "content": "hi"}])
    )
    assert "contents" in payload
    assert payload["generationConfig"]["maxOutputTokens"] == 1024


def test_azure_url_building():
    adapter = AzureOpenAIAdapter(
        api_key="k",
        endpoint="https://my.openai.azure.com/",
        deployment="gpt4o",
        api_version="2024-06-01",
    )
    req = _req("gpt-4o", Provider.AZURE_OPENAI, [{"role": "user", "content": "x"}])
    url = adapter._url(req)
    assert url == (
        "https://my.openai.azure.com/openai/deployments/gpt4o/chat/completions"
        "?api-version=2024-06-01"
    )


async def test_health_checks():
    assert await GeminiAdapter(api_key="k").health_check() is True
    assert await GeminiAdapter(api_key=None).health_check() is False
    assert await AzureOpenAIAdapter(api_key="k", endpoint="https://x").health_check() is True
    assert await AzureOpenAIAdapter(api_key="k", endpoint="").health_check() is False
    assert await OllamaAdapter().health_check() is True


def test_provider_names():
    assert GeminiAdapter().provider_name == "gemini"
    assert AzureOpenAIAdapter().provider_name == "azure_openai"
    assert OllamaAdapter().provider_name == "ollama"
