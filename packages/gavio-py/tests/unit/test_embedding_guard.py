"""Embedding call guard tests (F-SEC-10).

The same PII pipeline that protects completions must run on embedding calls:
inputs are scanned/redacted before the provider's embedding API, governance
and audit interceptors fire, and the inspector traces the call. Redaction
cases come from //test-vectors/embedding/redaction.json, shared with the
other SDKs.
"""

from __future__ import annotations

import io
import json
from pathlib import Path

import pytest

from gavio import Gateway
from gavio.exceptions import ProviderError
from gavio.inspector import InspectorConfig
from gavio.interceptors.audit import AuditInterceptor
from gavio.interceptors.audit.sinks.stdout import StdoutSink
from gavio.interceptors.pii import PiiGuard
from gavio.providers.base import ProviderAdapter
from gavio.providers.mock import MockProvider
from gavio.request import GavioRequest
from gavio.response import GavioResponse

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors" / "embedding" / "redaction.json"
_CASES = json.loads(_VECTORS.read_text())["cases"]


class _CapturingMockProvider(MockProvider):
    """Records the request as it reaches the embedding API (post-redaction)."""

    def __init__(self) -> None:
        super().__init__()
        self.embedded: GavioRequest | None = None

    async def embed(self, request: GavioRequest) -> GavioResponse:
        self.embedded = request
        return await super().embed(request)


def _gateway(adapter: MockProvider, *interceptors) -> Gateway:
    builder = Gateway.builder().adapter(adapter).model("mock")
    for interceptor in interceptors:
        builder.use(interceptor)
    return builder.build()


async def test_embed_returns_one_vector_per_text() -> None:
    gw = _gateway(MockProvider())
    response = await gw.embed(["alpha", "beta", "gamma"])
    assert response.embeddings is not None
    assert len(response.embeddings) == 3
    assert all(len(vector) == 8 for vector in response.embeddings)
    assert response.content == ""
    assert response.usage.prompt_tokens > 0
    # Deterministic: same text, same vector.
    again = await gw.embed(["alpha"])
    assert again.embeddings[0] == response.embeddings[0]


@pytest.mark.parametrize("case", _CASES, ids=lambda c: c["id"])
async def test_embedding_redaction_vectors(case: dict) -> None:
    adapter = _CapturingMockProvider()
    gw = _gateway(adapter, PiiGuard())
    response = await gw.embed(case["texts"])

    reached_provider = " \n ".join(m.get("content", "") for m in adapter.embedded.messages)
    expected = case["expected"]
    for fragment in expected["redactedContains"]:
        assert fragment in reached_provider, f"{case['id']}: missing {fragment!r}"
    for raw in expected["redactedNotContains"]:
        assert raw not in reached_provider, f"{case['id']}: leaked {raw!r}"
    assert response.embeddings is not None
    assert len(response.embeddings) == len(case["texts"])


async def test_embed_writes_audit_record_with_pii_metadata() -> None:
    gw = _gateway(
        MockProvider(),
        AuditInterceptor(sink=StdoutSink(stream=io.StringIO())),
        PiiGuard(),
    )
    response = await gw.embed(["reach me at jan.real@corp.com"])
    record = response.audit
    assert record is not None
    assert record.trace_id == response.trace_id
    assert record.prompt_hash
    assert "EMAIL" in record.pii_entity_types
    assert "pii_guard" in record.interceptors_fired


async def test_embed_raises_for_providers_without_embeddings() -> None:
    class _NoEmbedProvider(ProviderAdapter):
        @property
        def provider_name(self) -> str:
            return "mock"

        async def complete(self, request: GavioRequest) -> GavioResponse:
            raise AssertionError("not used")

        async def health_check(self) -> bool:
            return True

    gw = _gateway(_NoEmbedProvider())
    with pytest.raises(ProviderError, match="does not support embeddings"):
        await gw.embed(["anything"])


async def test_embed_is_traced_by_the_inspector() -> None:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .use(PiiGuard())
        .inspect(InspectorConfig(mode="metadata", start_server=False))
        .build()
    )
    events: list[dict] = []
    gw.inspector.bus.subscribe(events.append)
    await gw.embed(["mail jan@example.com please"])
    types = [e["type"] for e in events]
    assert types[0] == "trace.start"
    assert "provider.call.start" in types
    assert types[-1] == "trace.end"
    end = events[-1]["data"]
    assert end["status"] == "ok"
    assert "EMAIL" in end.get("piiEntityTypes", [])
