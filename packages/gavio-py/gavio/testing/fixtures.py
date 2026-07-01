"""Synthetic fixtures for tests. All PII here is fake — never real data."""

from __future__ import annotations

# Synthetic PII samples keyed by entity type. Values are invalid-for-real-use
# but pass the structural/checksum validators in the scanners.
PII_SAMPLES: dict[str, str] = {
    "EMAIL": "jan.devries@example.com",
    "IBAN": "NL91ABNA0417164300",  # valid mod-97 checksum
    "BSN": "111222333",  # valid 11-proef
    "CREDIT_CARD": "4111111111111111",  # valid Luhn test number
    "PHONE": "+31 6 12345678",
    "IP_ADDRESS": "192.168.1.42",
    "SSN": "123-45-6789",
    "SECRET": "sk-ant-abcdef0123456789ABCDEF0123",
}


def sample_messages(content: str = "Hello, world") -> list[dict[str, str]]:
    return [{"role": "user", "content": content}]


def message_with_pii() -> list[dict[str, str]]:
    text = (
        f"Email {PII_SAMPLES['EMAIL']} and transfer to "
        f"{PII_SAMPLES['IBAN']} by Friday."
    )
    return sample_messages(text)
