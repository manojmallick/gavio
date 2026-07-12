"""Gavio Prompt Registry + Evals - versioned templates and safe reports."""

from __future__ import annotations

import asyncio
import json

from gavio import EvalSuite, PromptRegistry, PromptTemplate


async def main() -> None:
    registry = PromptRegistry([
        PromptTemplate(
            id="support.reply",
            version="2026-07-12",
            messages=[
                {"role": "system", "content": "You are a concise support assistant."},
                {"role": "user", "content": "Reply to {{ customer }} about {{ topic }}."},
            ],
            required_variables=("customer", "topic"),
        )
    ])

    rendered = registry.render("support.reply", {"customer": "Avery", "topic": "refund"})
    suite = EvalSuite.from_dict({
        "id": "support-smoke",
        "cases": [
            {
                "id": "refund-pass",
                "templateId": "support.reply",
                "variables": {"customer": "Avery", "topic": "refund"},
                "assertions": [{"type": "contains", "value": "refund"}],
            },
            {
                "id": "refund-fail",
                "templateId": "support.reply",
                "variables": {"customer": "Avery", "topic": "refund"},
                "assertions": [{"type": "not_contains", "value": "card number"}],
            },
        ],
    })
    failure_output = "Hello Avery, please send your card number."
    outputs = {
        "refund-pass": "Hello Avery, your refund is approved.",
        "refund-fail": failure_output,
    }

    report = await suite.run(registry, lambda _prompt, case: outputs[case.id])
    serialized = json.dumps(report.to_dict())

    print(f"template={rendered.lineage.template_id}@{rendered.lineage.template_version}")
    print(f"score={report.score}")
    print(f"passed={report.passed_cases}/{report.total_cases}")
    print(f"output_hash_len={len(report.cases[0].output_hash)}")
    print(f"raw_output_stored={failure_output in serialized}")


if __name__ == "__main__":
    asyncio.run(main())
