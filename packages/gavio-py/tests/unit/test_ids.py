"""UUID v7 generation: version, variant, and time-ordering."""

from __future__ import annotations

from gavio._ids import new_trace_id, uuid7


def test_uuid7_version_and_variant():
    u = uuid7()
    assert u.version == 7
    # RFC 4122 variant bits (10xx).
    assert (u.int >> 62) & 0b11 == 0b10


def test_uuid7_is_time_ordered():
    ids = [str(uuid7()) for _ in range(50)]
    assert ids == sorted(ids)


def test_new_trace_id_unique():
    ids = {new_trace_id() for _ in range(100)}
    assert len(ids) == 100
