from __future__ import annotations

import json

from gavio import get_integration, integration_metadata


def main(integration_id: str) -> None:
    recipe = get_integration(integration_id)
    metadata = integration_metadata(
        integration_id,
        tenant="acme",
        feature="support-chat",
        environment="dev",
        workflow="offline-smoke",
    )
    payload = {
        "id": recipe.id,
        "name": recipe.name,
        "category": recipe.category,
        "metadata": metadata,
        "gavioSurfaces": list(recipe.gavio_surfaces),
        "recommendedExporters": list(recipe.recommended_exporters),
        "docsPath": recipe.docs_path,
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
