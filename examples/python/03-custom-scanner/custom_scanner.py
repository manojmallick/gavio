"""Gavio custom PII scanner — detect a domain-specific identifier.

Adds an ING bank-account scanner (a Dutch ING IBAN) alongside a built-in
scanner, then unit-tests it in isolation with GavioTestKit. Runs with no keys.

    pip install gavio
    python custom_scanner.py
"""

import asyncio
import re

from gavio import Gateway
from gavio.interceptors.pii import PiiGuard
from gavio.interceptors.pii.match import PiiMatch
from gavio.interceptors.pii.scanner import PiiScanner
from gavio.interceptors.pii.scanners import EmailScanner
from gavio.testing import GavioTestKit, MockProvider


class IngAccountScanner(PiiScanner):
    """Detects ING account numbers of the form NL##INGB##########."""

    entity_type = "ING_ACCOUNT"
    tier = 1

    _PATTERN = re.compile(r"\bNL\d{2}INGB\d{10}\b")

    def scan(self, text: str, ctx) -> list[PiiMatch]:
        return [
            PiiMatch(
                entity_type=self.entity_type,
                start=m.start(),
                end=m.end(),
                value=m.group(),
                replacement=f"[ING_ACCOUNT_{ctx.next_index(self.entity_type)}]",
            )
            for m in self._PATTERN.finditer(text)
        ]


async def demo_gateway() -> None:
    # Compose the custom scanner with a built-in one.
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(PiiGuard(scanners=[EmailScanner(), IngAccountScanner()]))
        .build()
    )
    resp = await gw.complete(
        messages=[
            {"role": "user", "content": "email jan@example.com, pay ING NL20INGB0001234567"}
        ]
    )
    print("Reply    :", resp.content)          # both values restored
    print("PII found:", resp.audit.pii_entity_types)  # ['EMAIL', 'ING_ACCOUNT']


async def test_in_isolation() -> None:
    # GavioTestKit runs the scanner against a mock provider — no network.
    kit = GavioTestKit(
        interceptors=[PiiGuard(scanners=[IngAccountScanner()])],
        provider=MockProvider(response="processed [ING_ACCOUNT_1]"),
    )
    result = await kit.run(
        messages=[{"role": "user", "content": "account NL20INGB0001234567 on file"}]
    )
    assert kit.pii_detected("ING_ACCOUNT")
    assert "NL20INGB0001234567" not in kit.redacted_request.messages[0]["content"]
    assert result.content == "processed NL20INGB0001234567"   # restored
    print("\n✓ custom scanner test passed →", result.content)


async def main() -> None:
    await demo_gateway()
    await test_in_isolation()


if __name__ == "__main__":
    asyncio.run(main())
