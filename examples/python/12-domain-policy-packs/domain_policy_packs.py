"""Gavio Domain Policy Pack Catalog.

    pip install -r requirements.txt
    python domain_policy_packs.py
"""

import asyncio

from gavio import Gateway
from gavio.interceptors.pii import PiiGuard, PolicyPack, list_policy_packs


async def main() -> None:
    healthcare = PolicyPack.load("healthcare")
    india = PolicyPack.load("regional/india")
    hr = PolicyPack.load("hr").with_overrides(
        {
            "detectors": {
                "employee_id": {
                    "action": "flag",
                    "severity": "critical",
                    "redactionStrategy": "hash",
                }
            }
        }
    )

    print("catalog :", ", ".join(list_policy_packs()))
    print("signed  :", healthcare.id, healthcare.verify_signature())
    print("override:", next(d for d in hr.manifest()["detectors"] if d["name"] == "employee_id"))

    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(PiiGuard.from_policy_pack(healthcare, india, hr, log_entity_types=False))
        .build()
    )
    resp = await gw.complete(
        messages=[
            {
                "role": "user",
                "content": (
                    "Patient MRN-123456 has member MEM-AB12CD34. "
                    "PAN ABCDE1234F and Aadhaar 1234 5678 9012 are present. "
                    "Template EMP-000000 is allowed, but EMP-123456 is real."
                ),
            }
        ]
    )

    print("reply    :", resp.content)
    print("PII found:", sorted(resp.audit.pii_entity_types))


if __name__ == "__main__":
    asyncio.run(main())
