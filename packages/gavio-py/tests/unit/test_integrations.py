from __future__ import annotations

import json
from pathlib import Path

import pytest

from gavio.integrations import (
    compatibility_matrix,
    get_integration,
    integration_adapter_payload,
    integration_metadata,
    list_integrations,
)

_VECTORS = Path(__file__).resolve().parents[4] / "test-vectors"


def _catalog() -> dict:
    return json.loads((_VECTORS / "integrations" / "catalog.json").read_text())


def _adapters() -> dict:
    return json.loads((_VECTORS / "integrations" / "adapters.json").read_text())


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


def test_integration_adapter_payloads_match_shared_vector() -> None:
    vector = _adapters()

    for case in vector["adapters"]:
        payload = integration_adapter_payload(
            case["id"],
            vector["source"],
            metadata=vector["metadata"],
        )
        assert payload["schemaVersion"] == "gavio.integration-adapter.v1"
        assert payload["adapter"] == case["id"]
        assert payload["target"] == case["id"]
        assert payload["kind"] == case["kind"]
        for expectation in case["expects"]:
            if expectation.get("absent"):
                assert _missing(payload, expectation["path"])
            else:
                assert _at(payload, expectation["path"]) == expectation["value"]
        serialized = json.dumps(payload, sort_keys=True)
        for forbidden in vector["forbiddenStrings"]:
            assert forbidden not in serialized


def _at(value: object, path: list[object]) -> object:
    current = value
    for part in path:
        if isinstance(part, int):
            assert isinstance(current, list)
            current = current[part]
        else:
            assert isinstance(current, dict)
            current = current[part]
    return current


def _missing(value: object, path: list[object]) -> bool:
    current = value
    for part in path:
        if isinstance(part, int):
            if not isinstance(current, list) or part >= len(current):
                return True
            current = current[part]
        else:
            if not isinstance(current, dict) or part not in current:
                return True
            current = current[part]
    return False
