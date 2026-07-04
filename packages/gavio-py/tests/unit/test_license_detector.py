"""Tests for the license / copyright detection validator (F-QUA-10)."""

from __future__ import annotations

import pytest

from gavio import Gateway
from gavio.exceptions import GuardrailViolationError
from gavio.interceptors.audit import AuditRecord
from gavio.interceptors.audit.interceptor import AuditInterceptor
from gavio.interceptors.audit.sink import AuditSink
from gavio.interceptors.guardrails import (
    GuardrailsInterceptor,
    LicenseDetectorValidator,
    detect_licenses,
)
from gavio.providers.mock import MockProvider

# Synthetic license snippets — fixtures only, mirror test-vectors/license.
MIT = (
    "Permission is hereby granted, free of charge, to any person obtaining a copy of "
    'this software and associated documentation files (the "Software"), to deal in the '
    "Software without restriction, including without limitation the rights to use, copy, "
    "modify, merge, publish, distribute, sublicense, and/or sell copies of the Software."
)
APACHE = (
    'Licensed under the Apache License, Version 2.0 (the "License"); you may not use this '
    "file except in compliance with the License. You may obtain a copy of the License at."
)
GPL3 = (
    "This program is free software: you can redistribute it and/or modify it under the "
    "terms of the GNU General Public License as published by the Free Software Foundation, "
    "either version 3 of the License, or (at your option) any later version."
)
GPL2 = (
    "This program is free software; you can redistribute it and/or modify it under the "
    "terms of the GNU General Public License as published by the Free Software Foundation; "
    "either version 2 of the License, or (at your option) any later version."
)
CLEAN = "def add(a, b): return a + b  # sums two numbers used across the project"


class CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


def _gw(response, *interceptors):
    b = Gateway.builder().adapter(MockProvider(response=response)).model("mock")
    for i in interceptors:
        b = b.use(i)
    return b.build()


def test_detect_each_license():
    assert detect_licenses(MIT) == ["MIT"]
    assert detect_licenses(APACHE) == ["Apache-2.0"]
    assert detect_licenses(GPL3) == ["GPL-3.0"]
    assert detect_licenses(GPL2) == ["GPL-2.0"]


def test_gpl_versions_not_confused():
    assert "GPL-3.0" not in detect_licenses(GPL2)
    assert "GPL-2.0" not in detect_licenses(GPL3)


def test_clean_content_detects_nothing():
    assert detect_licenses(CLEAN) == []
    assert detect_licenses("the quick brown fox jumps over the lazy dog every morning") == []


def test_multiple_licenses_sorted():
    assert detect_licenses(f"{MIT}\n\n{APACHE}") == ["Apache-2.0", "MIT"]


def test_licenses_subset_option():
    assert detect_licenses(MIT, licenses=["Apache-2.0"]) == []
    assert detect_licenses(MIT, licenses=["MIT"]) == ["MIT"]


def test_validator_name_and_reason():
    v = LicenseDetectorValidator()
    assert v.name == "license_detector"
    assert v.validate(CLEAN).ok
    res = v.validate(MIT)
    assert not res.ok
    assert res.reason == "license text detected: MIT"


async def test_guardrails_blocks_license_text():
    gw = _gw(MIT, GuardrailsInterceptor([LicenseDetectorValidator()]))
    with pytest.raises(GuardrailViolationError):
        await gw.complete(messages=[{"role": "user", "content": "q"}])


async def test_outcome_recorded_in_audit_record_on_warn():
    sink = CollectingSink()
    gw = _gw(
        APACHE,
        AuditInterceptor(sink),
        GuardrailsInterceptor([LicenseDetectorValidator()], on_failure="warn"),
    )
    r = await gw.complete(messages=[{"role": "user", "content": "q"}])
    assert r.content == APACHE  # warned, not blocked
    assert len(sink.records) == 1
    assert sink.records[0].guardrail_outcome == "FAIL"


async def test_clean_content_passes_with_pass_outcome():
    sink = CollectingSink()
    gw = _gw(
        CLEAN,
        AuditInterceptor(sink),
        GuardrailsInterceptor([LicenseDetectorValidator()]),
    )
    await gw.complete(messages=[{"role": "user", "content": "q"}])
    assert sink.records[0].guardrail_outcome == "PASS"
