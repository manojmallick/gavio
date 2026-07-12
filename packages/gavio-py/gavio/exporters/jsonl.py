"""JSONL runtime exporter."""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import TextIO

from .base import GavioRuntimeExporter, metadata_only_event


class JsonlRuntimeExporter(GavioRuntimeExporter):
    """Append runtime events as JSON lines.

    ``metadata_only`` defaults to True and strips content-bearing fields before
    every write. Pass ``stream`` in tests or ``path`` in applications.
    """

    def __init__(
        self,
        path: str | Path | None = None,
        *,
        stream: TextIO | None = None,
        metadata_only: bool = True,
    ) -> None:
        if path is None and stream is None:
            raise ValueError("JsonlRuntimeExporter requires path or stream")
        if path is not None and stream is not None:
            raise ValueError("pass either path or stream, not both")
        self.path = Path(path).expanduser() if path is not None else None
        if self.path is not None:
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self._stream = stream
        self._metadata_only = metadata_only
        self._lock = threading.Lock()

    def export_event(self, event: dict) -> None:
        payload = metadata_only_event(event) if self._metadata_only else event
        line = json.dumps(payload, separators=(",", ":"), sort_keys=True) + "\n"
        with self._lock:
            if self._stream is not None:
                self._stream.write(line)
                return
            assert self.path is not None
            with self.path.open("a", encoding="utf-8") as f:
                f.write(line)

    def flush(self) -> None:
        if self._stream is not None:
            self._stream.flush()
