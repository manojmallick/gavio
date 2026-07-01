"""StreamBuffer (F-REL-06) — accumulate a provider stream for post-interceptors.

Post-interceptors (guardrails, PII restore, audit) need the *complete* response,
so a streamed reply is buffered in full before the post pipeline runs and before
any chunk is handed to the caller. This trades first-token latency for the
guarantee that every interceptor sees — and can rewrite or block — the whole
response.
"""

from __future__ import annotations


class StreamBuffer:
    """Collects streamed text chunks into a single assembled response."""

    def __init__(self) -> None:
        self._parts: list[str] = []

    def append(self, chunk: str) -> None:
        """Add one streamed chunk."""
        self._parts.append(chunk)

    def text(self) -> str:
        """The full buffered response so far."""
        return "".join(self._parts)

    def __len__(self) -> int:
        return sum(len(p) for p in self._parts)
