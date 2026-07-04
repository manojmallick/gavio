"""Built-in tier-1 (regex) PII scanners."""

from __future__ import annotations

from .bsn import BsnScanner
from .credit_card import CreditCardScanner
from .email import EmailScanner
from .iban import IbanScanner
from .ip_address import IpAddressScanner
from .phone import PhoneScanner
from .routing_number import RoutingNumberScanner
from .secret import SecretScanner
from .ssn import SsnScanner
from .swift_bic import SwiftBicScanner

__all__ = [
    "BsnScanner",
    "CreditCardScanner",
    "EmailScanner",
    "IbanScanner",
    "IpAddressScanner",
    "PhoneScanner",
    "SecretScanner",
    "SsnScanner",
    "SwiftBicScanner",
    "RoutingNumberScanner",
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


def fintech_scanners() -> list:
    """FinTech domain policy pack — SWIFT/BIC and US ABA routing numbers.

    Compose with the defaults:
    ``PiiGuard(scanners=[*default_scanners(), *fintech_scanners()])``.
    (IBAN is already in the default set.)
    """
    return [SwiftBicScanner(), RoutingNumberScanner()]
