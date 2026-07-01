"""Built-in tier-1 (regex) PII scanners."""

from __future__ import annotations

from .bsn import BsnScanner
from .credit_card import CreditCardScanner
from .email import EmailScanner
from .iban import IbanScanner
from .ip_address import IpAddressScanner
from .phone import PhoneScanner
from .secret import SecretScanner
from .ssn import SsnScanner

__all__ = [
    "BsnScanner",
    "CreditCardScanner",
    "EmailScanner",
    "IbanScanner",
    "IpAddressScanner",
    "PhoneScanner",
    "SecretScanner",
    "SsnScanner",
]


def default_scanners() -> list:
    """The default scanner set wired into PiiGuard when none is supplied."""
    return [
        SecretScanner(),
        EmailScanner(),
        IbanScanner(),
        BsnScanner(),
        CreditCardScanner(),
        SsnScanner(),
        PhoneScanner(),
        IpAddressScanner(),
    ]
