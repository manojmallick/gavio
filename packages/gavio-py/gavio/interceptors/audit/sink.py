"""AuditSink — the extensible destination for audit records."""

from __future__ import annotations

from abc import ABC, abstractmethod

from .record import AuditRecord


class AuditSink(ABC):
    """Where audit records go. Implement ``write`` to add a backend."""

    @abstractmethod
    async def write(self, record: AuditRecord) -> None:
        ...

    async def close(self) -> None:
        """Flush/close any resources. Default no-op."""
        return None
