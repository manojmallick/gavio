"""Drop-in shims for other SDKs (F-DX-04)."""

from __future__ import annotations

from .openai import ChatCompletion, GavioOpenAI

__all__ = ["GavioOpenAI", "ChatCompletion"]
