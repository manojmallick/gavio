"""UUID v7 generation — time-sortable, unique identifiers for traces.

Python's stdlib only gains ``uuid.uuid7`` in 3.14, so we ship a compliant
generator here. UUID v7 layout (RFC 9562): 48-bit Unix millisecond timestamp,
4-bit version, 12 bits of randomness, 2-bit variant, 62 bits of randomness.
"""

from __future__ import annotations

import os
import threading
import time
import uuid

_lock = threading.Lock()
_last_ms = -1
_seq = 0  # 12-bit per-millisecond sequence in rand_a, for monotonicity


def _next_timestamp_and_seq() -> tuple[int, int]:
    """Return a (unix_ms, sequence) pair that is monotonically non-decreasing.

    Within a single millisecond the 12-bit sequence increments so IDs stay
    strictly ordered (RFC 9562 method 1). If the sequence overflows, the
    timestamp is nudged forward.
    """
    global _last_ms, _seq
    with _lock:
        now_ms = int(time.time() * 1000)
        if now_ms > _last_ms:
            _last_ms = now_ms
            _seq = int.from_bytes(os.urandom(2), "big") & 0x0FFF
        else:
            _seq += 1
            if _seq > 0x0FFF:
                _last_ms += 1
                _seq = 0
        return _last_ms, _seq


def uuid7() -> uuid.UUID:
    """Return a new UUID version 7 (time-ordered, monotonic within a process)."""
    unix_ms, rand_a = _next_timestamp_and_seq()

    # 48 bits of millisecond timestamp.
    time_high = (unix_ms >> 16) & 0xFFFFFFFF
    time_low = unix_ms & 0xFFFF

    rand_b = int.from_bytes(os.urandom(8), "big") & 0x3FFFFFFFFFFFFFFF  # 62 bits

    value = (
        (time_high << 96)
        | (time_low << 80)
        | (0x7 << 76)  # version 7
        | (rand_a << 64)
        | (0b10 << 62)  # variant
        | rand_b
    )
    return uuid.UUID(int=value)


def new_trace_id() -> str:
    """Return a fresh trace id as a string."""
    return str(uuid7())
