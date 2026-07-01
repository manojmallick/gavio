"""Audit-chain verification (F-OBS-02) and multi-agent DAG trace (F-OBS-03)."""

from __future__ import annotations

from dataclasses import dataclass, field

from .record import AuditRecord


def verify_chain(records: list[AuditRecord]) -> bool:
    """Return True if the records form an intact hash chain.

    Each record's ``previous_hash`` must equal the SHA-256 content hash of the
    record before it. The first record's ``previous_hash`` must be empty.
    Any edit, reorder, or deletion breaks the chain.
    """
    prev_hash = ""
    for rec in records:
        if rec.previous_hash != prev_hash:
            return False
        prev_hash = rec.content_hash()
    return True


@dataclass
class TraceNode:
    """A node in the multi-agent call graph."""

    trace_id: str
    agent_id: str | None
    parent_trace_id: str | None
    children: list[TraceNode] = field(default_factory=list)


def build_call_graph(records: list[AuditRecord]) -> list[TraceNode]:
    """Reconstruct the multi-agent DAG from audit records.

    Uses ``parent_trace_id`` + ``trace_id`` to link calls. Returns the root
    nodes (those with no known parent). A single agent call yields one root.
    """
    nodes: dict[str, TraceNode] = {}
    for rec in records:
        nodes[rec.trace_id] = TraceNode(
            trace_id=rec.trace_id,
            agent_id=rec.agent_id,
            parent_trace_id=rec.parent_trace_id,
        )

    roots: list[TraceNode] = []
    for node in nodes.values():
        parent = nodes.get(node.parent_trace_id) if node.parent_trace_id else None
        if parent is not None:
            parent.children.append(node)
        else:
            roots.append(node)
    return roots
