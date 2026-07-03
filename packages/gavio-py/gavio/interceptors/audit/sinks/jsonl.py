"""JsonlSink — append-only JSON-lines audit store (F-DX-08).

One :meth:`AuditRecord.to_json` line per record. The resulting file is the
store ``gavio inspect --store audit.jsonl`` serves the read-only dashboard
from, and the input :func:`gavio.inspector.store.verify_chain_records` walks.
"""

from __future__ import annotations

import threading
from pathlib import Path

from ..record import AuditRecord
from ..sink import AuditSink


class JsonlSink(AuditSink):
    """Append each audit record as one JSON line. Zero dependencies."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).expanduser()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()

    async def write(self, record: AuditRecord) -> None:
        line = record.to_json() + "\n"
        with self._lock, self.path.open("a", encoding="utf-8") as f:
            f.write(line)
