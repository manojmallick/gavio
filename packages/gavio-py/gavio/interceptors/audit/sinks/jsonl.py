"""JsonlSink — append-only JSON-lines audit store (F-DX-08).

One :meth:`AuditRecord.to_json` line per record. The resulting file is the
store ``gavio inspect --store audit.jsonl`` serves the read-only dashboard
from, and the input :func:`gavio.inspector.store.verify_chain_records` walks.
"""

from __future__ import annotations

import json
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

    async def purge(self, subject_id: str) -> int:
        """Drop every line whose ``subject_id`` matches; return the count removed.

        Rewrites the file atomically via a temp file + replace. Malformed lines
        are preserved untouched. A missing file yields 0.
        """
        with self._lock:
            if not self.path.exists():
                return 0
            kept: list[str] = []
            removed = 0
            for line in self.path.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                try:
                    if json.loads(line).get("subject_id") == subject_id:
                        removed += 1
                        continue
                except (json.JSONDecodeError, ValueError):
                    pass  # preserve non-JSON lines untouched
                kept.append(line)
            if removed:
                tmp = self.path.with_suffix(self.path.suffix + ".tmp")
                tmp.write_text("".join(f"{line}\n" for line in kept), encoding="utf-8")
                tmp.replace(self.path)
            return removed
