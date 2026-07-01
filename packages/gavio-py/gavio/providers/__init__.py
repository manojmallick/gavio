"""Provider adapters and the provider registry."""

from __future__ import annotations

from ..exceptions import ConfigurationError
from ..pricing import PricingProvider
from ..types import Provider
from .anthropic import AnthropicAdapter
from .azure_openai import AzureOpenAIAdapter
from .base import ProviderAdapter
from .gemini import GeminiAdapter
from .mock import MockProvider
from .ollama import OllamaAdapter
from .openai import OpenAIAdapter

__all__ = [
    "Provider",
    "ProviderAdapter",
    "MockProvider",
    "OpenAIAdapter",
    "AnthropicAdapter",
    "GeminiAdapter",
    "AzureOpenAIAdapter",
    "OllamaAdapter",
    "build_adapter",
]

# Provider -> adapter factory.
# v0.1.0: OpenAI, Anthropic, Mock. v0.2.0 adds Gemini, Azure OpenAI, Ollama.
_REGISTRY = {
    Provider.OPENAI: OpenAIAdapter,
    Provider.ANTHROPIC: AnthropicAdapter,
    Provider.GEMINI: GeminiAdapter,
    Provider.AZURE_OPENAI: AzureOpenAIAdapter,
    Provider.OLLAMA: OllamaAdapter,
    Provider.MOCK: MockProvider,
}


def build_adapter(
    provider: Provider | str,
    pricing: PricingProvider | None = None,
) -> ProviderAdapter:
    """Instantiate the default adapter for a provider id."""
    provider = Provider.coerce(provider)
    factory = _REGISTRY.get(provider)
    if factory is None:
        available = ", ".join(p.value for p in _REGISTRY)
        raise ConfigurationError(
            f"Provider {provider.value!r} is not available in v0.1.0 "
            f"(available: {available})"
        )
    return factory(pricing=pricing)
