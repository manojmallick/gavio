"""Audit sinks — destinations for AuditRecords."""

from __future__ import annotations

from .stdout import StdoutSink

__all__ = ["StdoutSink"]
