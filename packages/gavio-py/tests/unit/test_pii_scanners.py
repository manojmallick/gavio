"""Tests for the tier-1 PII scanners — synthetic data only."""

from __future__ import annotations

from gavio.interceptors.pii.context import ScanContext
from gavio.interceptors.pii.scanners import (
    BsnScanner,
    CreditCardScanner,
    EmailScanner,
    IbanScanner,
    IpAddressScanner,
    PhoneScanner,
    SecretScanner,
    SsnScanner,
)


def _scan(scanner, text):
    return scanner.scan(text, ScanContext())


def test_email_detected():
    matches = _scan(EmailScanner(), "ping jan.devries@example.com please")
    assert len(matches) == 1
    assert matches[0].entity_type == "EMAIL"
    assert matches[0].value == "jan.devries@example.com"
    assert matches[0].replacement == "[EMAIL_1]"


def test_iban_valid_checksum_detected():
    matches = _scan(IbanScanner(), "Transfer to NL91ABNA0417164300 today")
    assert len(matches) == 1
    assert matches[0].entity_type == "IBAN"


def test_iban_invalid_checksum_ignored():
    # Same structure, broken check digits.
    matches = _scan(IbanScanner(), "Transfer to NL00ABNA0417164300 today")
    assert matches == []


def test_bsn_eleven_proef():
    assert _scan(BsnScanner(), "bsn 111222333")  # valid
    assert _scan(BsnScanner(), "bsn 111222334") == []  # invalid


def test_credit_card_luhn():
    assert _scan(CreditCardScanner(), "card 4111111111111111")  # valid Luhn
    assert _scan(CreditCardScanner(), "card 4111111111111112") == []  # invalid


def test_phone_detected():
    matches = _scan(PhoneScanner(), "call +31 6 12345678 tomorrow")
    assert matches and matches[0].entity_type == "PHONE"


def test_phone_ignores_short_numbers():
    assert _scan(PhoneScanner(), "in the year 2026") == []


def test_ipv4_and_ipv6():
    assert _scan(IpAddressScanner(), "host 192.168.1.42")
    assert _scan(IpAddressScanner(), "host 2001:db8::1")
    assert _scan(IpAddressScanner(), "host 999.999.999.999") == []


def test_ssn_detected():
    matches = _scan(SsnScanner(), "ssn 123-45-6789")
    assert matches and matches[0].entity_type == "SSN"


def test_secret_scanner_keys_and_jwt():
    text = (
        "key sk-ant-abcdef0123456789ABCDEF0123 and "
        "token eyJhbGc.eyJzdWIi.SflKxwRJ and AKIAIOSFODNN7EXAMPLE"
    )
    matches = _scan(SecretScanner(), text)
    assert len(matches) >= 3
    assert all(m.entity_type == "SECRET" for m in matches)
