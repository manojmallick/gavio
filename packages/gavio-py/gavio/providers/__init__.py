"""Provider adapters and the provider registry."""

from __future__ import annotations

from ..exceptions import ConfigurationError
from ..pricing import PricingProvider
from ..types import Provider
from .anthropic import AnthropicAdapter
from .base import ProviderAdapter
from .mock import MockProvider
from .openai import OpenAIAdapter

__all__ = [
    "Provider",
    "ProviderAdapter",
    "MockProvider",
    "OpenAIAdapter",
    "AnthropicAdapter",
    "build_adapter",
]

# Provider -> adapter factory. v0.1.0 ships OpenAI, Anthropic, and Mock.
_REGISTRY = {
    Provider.OPENAI: OpenAIAdapter,
    Provider.ANTHROPIC: AnthropicAdapter,
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
