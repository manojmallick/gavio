"""Audit sinks — destinations for AuditRecords."""

from __future__ import annotations

from .jsonl import JsonlSink
from .stdout import StdoutSink

__all__ = ["JsonlSink", "StdoutSink"]
