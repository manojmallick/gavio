"""AuditSink — the extensible destination for audit records."""

from __future__ import annotations

from abc import ABC, abstractmethod

from .record import AuditRecord


class AuditSink(ABC):
    """Where audit records go. Implement ``write`` to add a backend."""

    @abstractmethod
    async def write(self, record: AuditRecord) -> None: ...

    async def purge(self, subject_id: str) -> int:
        """Erase records for a data subject (GDPR Art. 17, F-QUA-09).

        Remove every persisted record whose ``subject_id`` matches and return
        the number removed. The default is a no-op returning 0 — appropriate for
        non-persistent sinks (e.g. stdout). Persistent sinks override this.
        """
        return 0

    async def close(self) -> None:
        """Flush/close any resources. Default no-op."""
        return None
