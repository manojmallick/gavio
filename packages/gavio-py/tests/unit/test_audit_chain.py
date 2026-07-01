"""Tests for hash-chain audit (F-OBS-02) and multi-agent DAG trace (F-OBS-03)."""

from __future__ import annotations

import dataclasses

from gavio import Gateway
from gavio.interceptors.audit import (
    AuditInterceptor,
    AuditRecord,
    build_call_graph,
    verify_chain,
)
from gavio.interceptors.audit.sink import AuditSink


class CollectingSink(AuditSink):
    def __init__(self) -> None:
        self.records: list[AuditRecord] = []

    async def write(self, record: AuditRecord) -> None:
        self.records.append(record)


async def test_hash_chain_links_records():
    sink = CollectingSink()
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(AuditInterceptor(sink=sink, hash_chain=True))
        .build()
    )
    for i in range(3):
        await gw.complete(messages=[{"role": "user", "content": f"msg {i}"}])

    assert len(sink.records) == 3
    assert sink.records[0].previous_hash == ""
    assert verify_chain(sink.records) is True


async def test_tampering_breaks_chain():
    sink = CollectingSink()
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(AuditInterceptor(sink=sink, hash_chain=True))
        .build()
    )
    for i in range(3):
        await gw.complete(messages=[{"role": "user", "content": f"msg {i}"}])

    # Tamper with the middle record's cost — chain must no longer verify.
    tampered = list(sink.records)
    tampered[1] = dataclasses.replace(tampered[1], cost_usd=999.0)
    assert verify_chain(tampered) is False


async def test_multi_agent_dag_reconstruction():
    sink = CollectingSink()
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(AuditInterceptor(sink=sink))
        .build()
    )
    # Orchestrator call, then two sub-agent calls whose parent is the root trace.
    root = await gw.complete(
        messages=[{"role": "user", "content": "orchestrate"}], agent_id="orchestrator"
    )
    await gw.complete(
        messages=[{"role": "user", "content": "sub a"}],
        agent_id="agent-a",
        parent_trace_id=root.trace_id,
    )
    await gw.complete(
        messages=[{"role": "user", "content": "sub b"}],
        agent_id="agent-b",
        parent_trace_id=root.trace_id,
    )

    roots = build_call_graph(sink.records)
    assert len(roots) == 1
    assert roots[0].agent_id == "orchestrator"
    assert {c.agent_id for c in roots[0].children} == {"agent-a", "agent-b"}
