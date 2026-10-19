"""Root run resolution (F-REL-09, shared with F-AGT-01 task records).

A *root run* is every provider call that belongs to one agent task. Each call is counted under
a root run id, resolved per request in this order:

1. ``metadata["runId"]`` (or ``metadata["run_id"]``) when it is a non-empty string;
2. else the root of the ``parent_trace_id`` chain;
3. else the request's own ``trace_id``.

The chain root comes from a bounded least-recently-used map of ``trace_id -> root run id``.
Pure: no clock, no I/O. Behaviour is pinned by ``test-vectors/loop-guard/decisions.json``
(``rootRunCases``) and described in ``spec/README.md`` ('Loop guard').
"""

from __future__ import annotations

import threading
from collections import OrderedDict
from collections.abc import Mapping
from typing import Any

from .exceptions import ConfigurationError

DEFAULT_MAX_ENTRIES = 10_000


def explicit_run_id(metadata: Mapping[str, Any] | None) -> str | None:
    """The run id a caller set in request metadata, or None.

    Blank and non-string values are ignored, so a stray ``runId: 42`` cannot merge runs.
    """
    if not metadata:
        return None
    for key in ("runId", "run_id"):
        value = metadata.get(key)
        if isinstance(value, str) and value:
            return value
    return None


class RootRunResolver:
    """Resolves a request to its root run id, remembering the chain in a bounded LRU map.

    A ``parent_trace_id`` the map does not know — evicted, or from another process — is taken
    for the root itself, so pass ``metadata["runId"]`` when a run spans processes or outlives
    the map. Safe to share between threads.
    """

    def __init__(self, max_entries: int = DEFAULT_MAX_ENTRIES) -> None:
        if not isinstance(max_entries, int) or isinstance(max_entries, bool) or max_entries < 1:
            raise ConfigurationError("max_entries must be an integer of at least 1")
        self._max_entries = max_entries
        self._roots: OrderedDict[str, str] = OrderedDict()
        self._lock = threading.Lock()

    def resolve(
        self,
        trace_id: str,
        parent_trace_id: str | None = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> str:
        """The root run id of this call; also records ``trace_id`` so children resolve to it."""
        explicit = explicit_run_id(metadata)
        with self._lock:
            if explicit is not None:
                root = explicit
            elif parent_trace_id:
                known = self._roots.get(parent_trace_id)
                if known is not None:
                    self._roots.move_to_end(parent_trace_id)  # reading a parent refreshes it
                root = known if known is not None else parent_trace_id
            else:
                root = trace_id
            self._roots[trace_id] = root
            self._roots.move_to_end(trace_id)
            while len(self._roots) > self._max_entries:
                self._roots.popitem(last=False)
            return root

    def __len__(self) -> int:
        with self._lock:
            return len(self._roots)
