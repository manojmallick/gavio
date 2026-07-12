"""Gavio Policy Packs - core PII, FinTech, and custom regex rules.

    pip install -r requirements.txt
    python policy_packs.py
"""

import asyncio

from gavio import Gateway
from gavio.interceptors.pii import (
    PiiGuard,
    RegexPolicyRule,
    core_policy_pack,
    custom_policy_pack,
    fintech_policy_pack,
    policy_pack_scanners,
)


async def main() -> None:
    core = core_policy_pack()
    fintech = fintech_policy_pack()
    internal = custom_policy_pack(
        id="acme.internal",
        name="Acme Internal IDs",
        rules=[
            RegexPolicyRule(
                name="employee_id",
                entity_type="EMPLOYEE_ID",
                pattern=r"\bEMP-[0-9]{6}\b",
                confidence=0.92,
                replacement_prefix="EMPLOYEE_ID",
                action="flag",
                redaction_strategy="hash",
                label="INTERNAL_IDENTIFIER",
            )
        ],
        default_action="flag",
        redaction_strategy="hash",
        audit_labels=["INTERNAL_IDENTIFIER"],
    )

    print("packs:", core.manifest()["id"], fintech.manifest()["id"], internal.manifest()["id"])
    print("fintech detectors:", [d["entityType"] for d in fintech.manifest()["detectors"]])

    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(PiiGuard(scanners=policy_pack_scanners(core, fintech, internal)))
        .build()
    )
    resp = await gw.complete(
        messages=[
            {
                "role": "user",
                "content": (
                    "Wire SWIFT DEUTDEFF500 routing 111000025 for EMP-123456 "
                    "and email jan@example.com."
                ),
            }
        ]
    )

    print("reply    :", resp.content)
    print("PII found:", sorted(resp.audit.pii_entity_types))
    print("fired    :", resp.interceptors_fired)


if __name__ == "__main__":
    asyncio.run(main())
