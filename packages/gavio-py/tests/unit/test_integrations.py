from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio.integrations import (
    compatibility_matrix,
    get_integration,
    integration_metadata,
    list_integrations,
)

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


def _catalog() -> dict:
    return json.loads((_VECTORS / "integrations" / "catalog.json").read_text())


def test_integration_catalog_matches_shared_vector() -> None:
    expected = _catalog()["recipes"]
    actual = [recipe.to_dict() for recipe in list_integrations()]

    assert actual == expected


def test_integration_metadata_adds_request_labels() -> None:
    labels = integration_metadata(
        "litellm",
        tenant="acme",
        feature="support-chat",
        environment="prod",
    )

    assert labels == {
        "gateway": "litellm",
        "integration": "litellm",
        "integration_kind": "gateway",
        "tenant": "acme",
        "feature": "support-chat",
        "environment": "prod",
    }


def test_integration_helpers_filter_and_raise_cleanly() -> None:
    assert [recipe.id for recipe in list_integrations(category="observability")] == [
        "langfuse",
        "openlit",
    ]
    assert get_integration("openlit").recommended_exporters == ("otel",)
    with pytest.raises(KeyError, match="unknown Gavio integration"):
        get_integration("missing")


def test_compatibility_matrix_omits_metadata_payloads() -> None:
    matrix = compatibility_matrix()

    assert len(matrix) == len(_catalog()["recipes"])
    assert "metadata" not in matrix[0]
    assert matrix[0]["docsPath"] == "docs/integrations/litellm.md"


def test_integration_docs_and_examples_exist() -> None:
    repo_root = _VECTORS.parent

    for recipe in list_integrations():
        assert (repo_root / recipe.docs_path).is_file(), recipe.docs_path
        assert (repo_root / recipe.example_path).is_file(), recipe.example_path
