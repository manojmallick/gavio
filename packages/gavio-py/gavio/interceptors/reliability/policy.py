"""ExecutorPolicy re-export.

The class now lives in :mod:`gavio.interceptors.executor` so both the
reliability policies and the cache can share it. Kept here for backward
compatibility with existing imports.
"""

from __future__ import annotations

from ..executor import ExecutorPolicy

__all__ = ["ExecutorPolicy"]
