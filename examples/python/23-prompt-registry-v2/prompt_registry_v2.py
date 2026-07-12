from __future__ import annotations

import json
from pathlib import Path

from gavio.prompts import PromptRegistry, verify_prompt_manifest_signature

HERE = Path(__file__).resolve().parent
MANIFEST_PATH = HERE / "prompts.json"
SIGNING_SECRET = "registry-v2-test-secret"


def main() -> None:
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    if not verify_prompt_manifest_signature(manifest, SIGNING_SECRET):
        raise SystemExit("prompt manifest signature failed verification")

    registry = PromptRegistry.from_file(MANIFEST_PATH, verify_secret=SIGNING_SECRET)
    template = registry.get("support.reply", "^1.0.0")
    rendered = registry.render(
        "support.reply",
        {"customerName": "Avery", "topic": "refund status", "orderId": "A-100"},
        version="^1.0.0",
    )
    diff = registry.diff("support.reply", "1.0.0", "1.1.0")
    approval = template.approval.status if template.approval else "unreviewed"

    print(f"selected={template.id}@{template.version} approval={approval}")
    print(rendered.messages[-1]["content"])
    print(json.dumps(diff.to_dict(), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
