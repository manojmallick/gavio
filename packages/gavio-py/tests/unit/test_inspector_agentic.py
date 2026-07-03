"""Inspector v0.7.0 tests — DAG, sessions, stats, replay, export, store mode.

Covers F-OBS-10 (agent call graph + sessions), F-DX-11 (replay & edit-resend),
F-DX-08 (read-only dashboard over a JSONL audit store, hash-chain verify) and
F-DX-12 (export trace as test case). The DAG-assembly and replay-gating cases
come from //test-vectors/inspector/api-cases.json, shared with the other SDKs.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from gavio import Gateway
from gavio.inspector import InspectorConfig, open_store
from gavio.inspector.analytics import build_dag, build_sessions, build_stats
from gavio.interceptors.audit import AuditInterceptor, JsonlSink
from gavio.interceptors.pii import PiiGuard
from gavio.providers.mock import MockProvider

# repo_root/packages/gavio-py/tests/unit/... -> repo_root/test-vectors
_API_CASES = json.loads(
    (
        Path(__file__).resolve().parents[4] / "test-vectors" / "inspector" / "api-cases.json"
    ).read_text()
)


def _gateway(mode: str = "full", **config_kwargs) -> Gateway:
    return (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .inspect(
            InspectorConfig(mode=mode, port=0, unsafe_content_capture_ack=True, **config_kwargs)
        )
        .build()
    )


def _get(base: str, path: str) -> dict:
    with urllib.request.urlopen(base + path) as r:
        return json.loads(r.read())


def _post(base: str, path: str, body: dict) -> dict:
    request = urllib.request.Request(
        base + path,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request) as r:
        return json.loads(r.read())


def _http_status(excinfo: pytest.ExceptionInfo) -> int:
    return excinfo.value.code


async def _run_family(gw: Gateway) -> tuple[str, str, str]:
    """One orchestrator trace with two children in session s1."""
    root = await gw.complete(
        messages=[{"role": "user", "content": "orchestrate"}],
        agent_id="orchestrator",
        session_id="s1",
    )
    child_a = await gw.complete(
        messages=[{"role": "user", "content": "sub-task a"}],
        agent_id="worker-a",
        parent_trace_id=root.trace_id,
        session_id="s1",
    )
    child_b = await gw.complete(
        messages=[{"role": "user", "content": "sub-task b"}],
        agent_id="worker-b",
        parent_trace_id=root.trace_id,
        session_id="s1",
    )
    return root.trace_id, child_a.trace_id, child_b.trace_id


# ---- F-OBS-10: DAG + sessions ------------------------------------------------


async def test_dag_endpoint_builds_call_graph_with_rollups() -> None:
    gw = _gateway()
    try:
        root_id, child_a, child_b = await _run_family(gw)
        base = f"http://127.0.0.1:{gw.inspector.server.port}"

        dag = _get(base, f"/api/dag?root={root_id}")
        assert {n["traceId"] for n in dag["nodes"]} == {root_id, child_a, child_b}
        assert {(e["from"], e["to"]) for e in dag["edges"]} == {
            (root_id, child_a),
            (root_id, child_b),
        }
        root_node = next(n for n in dag["nodes"] if n["traceId"] == root_id)
        assert root_node["agentId"] == "orchestrator"
        assert root_node["subtree"]["traces"] == 3
        assert root_node["subtree"]["errors"] == 0
        leaf = next(n for n in dag["nodes"] if n["traceId"] == child_a)
        assert leaf["subtree"]["traces"] == 1

        by_session = _get(base, "/api/dag?session_id=s1")
        assert len(by_session["nodes"]) == 3

        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _get(base, "/api/dag")
        assert _http_status(excinfo) == 400
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _get(base, "/api/dag?root=no-such-trace")
        assert _http_status(excinfo) == 404
    finally:
        gw.inspector.stop()


async def test_sessions_endpoint_aggregates_by_session() -> None:
    gw = _gateway()
    try:
        await _run_family(gw)
        await gw.complete(messages=[{"role": "user", "content": "no session"}])
        base = f"http://127.0.0.1:{gw.inspector.server.port}"

        sessions = _get(base, "/api/sessions")["sessions"]
        assert len(sessions) == 1
        s1 = sessions[0]
        assert s1["sessionId"] == "s1"
        assert s1["traces"] == 3
        assert s1["errors"] == 0
        assert sorted(s1["agents"]) == ["orchestrator", "worker-a", "worker-b"]
        assert s1["firstWallTimeUtc"] <= s1["lastWallTimeUtc"]
    finally:
        gw.inspector.stop()


# ---- F-DX-08: stats ------------------------------------------------------------


async def test_stats_endpoint_red_aggregates_and_grouping() -> None:
    gw = _gateway()
    try:
        await _run_family(gw)
        base = f"http://127.0.0.1:{gw.inspector.server.port}"

        stats = _get(base, "/api/stats")
        total = stats["total"]
        assert total["requests"] == 3
        assert total["errors"] == 0
        assert total["errorRate"] == 0.0
        assert total["latencyMs"]["p50"] is not None
        assert total["tokens"]["total"] > 0
        assert total["cacheHitRate"] == 0.0

        grouped = _get(base, "/api/stats?group_by=agent_id")
        assert set(grouped["groups"]) == {"orchestrator", "worker-a", "worker-b"}
        assert grouped["groups"]["worker-a"]["requests"] == 1

        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _get(base, "/api/stats?group_by=nope")
        assert _http_status(excinfo) == 400
    finally:
        gw.inspector.stop()


def test_stats_counts_pii_and_errors_from_summaries() -> None:
    summaries = [
        {"traceId": "a", "status": "ok", "latencyMs": 10, "piiEntityTypes": ["EMAIL"]},
        {"traceId": "b", "status": "error", "latencyMs": 30, "piiEntityTypes": ["EMAIL", "IBAN"]},
    ]
    total = build_stats(summaries)["total"]
    assert total["errors"] == 1
    assert total["errorRate"] == 0.5
    assert total["piiDetections"] == {"EMAIL": 2, "IBAN": 1}
    assert total["latencyMs"]["p50"] == 10
    assert total["latencyMs"]["p99"] == 30


# ---- F-DX-11: replay -------------------------------------------------------------


async def test_replay_refires_trace_and_returns_new_trace_id() -> None:
    gw = _gateway()
    try:
        response = await gw.complete(messages=[{"role": "user", "content": "replay me"}])
        base = f"http://127.0.0.1:{gw.inspector.server.port}"

        replayed = _post(base, "/api/replay", {"traceId": response.trace_id})
        assert replayed["replayOf"] == response.trace_id
        assert replayed["traceId"] != response.trace_id
        # The replayed call went through the live pipeline into the buffer.
        new_trace = _get(base, f"/api/traces/{replayed['traceId']}")
        assert new_trace["summary"]["status"] == "ok"

        edited = _post(
            base,
            "/api/replay",
            {
                "traceId": response.trace_id,
                "overrides": {"messages": [{"role": "user", "content": "edited"}]},
            },
        )
        edited_trace = _get(base, f"/api/traces/{edited['traceId']}")
        start = next(e for e in edited_trace["events"] if e["type"] == "trace.start")
        assert start["data"]["messages"][0]["content"] == "edited"

        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _post(base, "/api/replay", {"traceId": "no-such-trace"})
        assert _http_status(excinfo) == 404
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _post(base, "/api/replay", {})
        assert _http_status(excinfo) == 400
    finally:
        gw.inspector.stop()


async def test_replay_is_403_outside_full_mode() -> None:
    gw = _gateway(mode="redacted")
    try:
        response = await gw.complete(messages=[{"role": "user", "content": "hi"}])
        base = f"http://127.0.0.1:{gw.inspector.server.port}"
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _post(base, "/api/replay", {"traceId": response.trace_id})
        assert _http_status(excinfo) == 403
    finally:
        gw.inspector.stop()


# ---- cost simulator ---------------------------------------------------------------


async def test_simulate_cost_recosts_trace_under_other_model() -> None:
    gw = _gateway()
    try:
        response = await gw.complete(messages=[{"role": "user", "content": "price this call"}])
        base = f"http://127.0.0.1:{gw.inspector.server.port}"

        simulated = _get(base, f"/api/simulate-cost?trace_id={response.trace_id}&model=gpt-4o")
        assert simulated["traceId"] == response.trace_id
        assert simulated["simulatedModel"] == "gpt-4o"
        assert simulated["simulatedCostUsd"] > 0.0  # mock is free, gpt-4o is not
        assert simulated["deltaUsd"] == pytest.approx(
            simulated["simulatedCostUsd"] - simulated["costUsd"]
        )
        assert simulated["usage"]["totalTokens"] > 0

        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _get(base, "/api/simulate-cost?trace_id=missing-both")
        assert _http_status(excinfo) == 400
    finally:
        gw.inspector.stop()


# ---- F-DX-12: export -----------------------------------------------------------------


async def test_export_test_vector_sanitizes_pii() -> None:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .use(PiiGuard())
        .inspect(InspectorConfig(mode="full", port=0, unsafe_content_capture_ack=True))
        .build()
    )
    try:
        response = await gw.complete(
            messages=[{"role": "user", "content": "mail bob.real@corp.com about the invoice"}]
        )
        base = f"http://127.0.0.1:{gw.inspector.server.port}"

        with urllib.request.urlopen(
            f"{base}/api/traces/{response.trace_id}/export?format=test-vector"
        ) as r:
            case = json.loads(r.read())
        assert case["id"].startswith("exported-")
        assert case["mode"] == "full"
        assert "pii_guard" in case["interceptors"]
        content = case["request"]["messages"][0]["content"]
        assert "bob.real@corp.com" not in content
        assert "jan@example.com" in content
        assert [e["type"] for e in case["expectedEvents"]][0] == "trace.start"
        assert case["expectedEvents"][-1]["type"] == "trace.end"

        with urllib.request.urlopen(
            f"{base}/api/traces/{response.trace_id}/export?format=testkit-py"
        ) as r:
            source = r.read().decode()
        assert "GavioTestKit" in source
        assert "bob.real@corp.com" not in source

        with pytest.raises(urllib.error.HTTPError) as excinfo:
            urllib.request.urlopen(f"{base}/api/traces/{response.trace_id}/export?format=nope")
        assert _http_status(excinfo) == 400
    finally:
        gw.inspector.stop()


async def test_export_is_403_in_metadata_mode() -> None:
    gw = _gateway(mode="metadata")
    try:
        response = await gw.complete(messages=[{"role": "user", "content": "hi"}])
        base = f"http://127.0.0.1:{gw.inspector.server.port}"
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            urllib.request.urlopen(
                f"{base}/api/traces/{response.trace_id}/export?format=test-vector"
            )
        assert _http_status(excinfo) == 403
    finally:
        gw.inspector.stop()


# ---- F-DX-08: store mode + hash-chain verify ---------------------------------------


async def _write_store(path: Path, tamper: bool = False) -> list[str]:
    gw = (
        Gateway.builder()
        .adapter(MockProvider())
        .model("mock")
        .use(AuditInterceptor(sink=JsonlSink(path), hash_chain=True))
        .build()
    )
    trace_ids = []
    for i in range(3):
        response = await gw.complete(
            messages=[{"role": "user", "content": f"call {i}"}],
            agent_id="orchestrator" if i == 0 else f"worker-{i}",
            session_id="store-session",
        )
        trace_ids.append(response.trace_id)
    if tamper:
        lines = path.read_text().splitlines()
        lines[1] = lines[1].replace('"cost_usd": 0.0', '"cost_usd": 99.0')
        path.write_text("\n".join(lines) + "\n")
    return trace_ids


async def test_store_mode_serves_readonly_dashboard(tmp_path: Path) -> None:
    store = tmp_path / "audit.jsonl"
    trace_ids = await _write_store(store)

    inspector = open_store(store, port=0)
    inspector.start_server()
    try:
        base = f"http://127.0.0.1:{inspector.server.port}"

        health = _get(base, "/api/health")
        assert health["mode"] == "metadata"
        assert health["traces"] == 3

        traces = _get(base, "/api/traces")["traces"]
        assert [t["traceId"] for t in traces] == trace_ids
        assert all(t["promptHash"] for t in traces)

        # search-by-content-hash: prefix lookup on the stored prompt hash
        prefix = traces[0]["promptHash"][:12]
        hits = _get(base, f"/api/traces?q={prefix}")["traces"]
        assert [t["traceId"] for t in hits] == [trace_ids[0]]

        verdict = _get(base, "/api/chain/verify")
        assert verdict == {"intact": True, "records": 3, "firstBrokenTraceId": None}

        sessions = _get(base, "/api/sessions")["sessions"]
        assert sessions[0]["traces"] == 3

        stats = _get(base, "/api/stats")
        assert stats["total"]["requests"] == 3

        # metadata mode: no replay, no export
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _post(base, "/api/replay", {"traceId": trace_ids[0]})
        assert _http_status(excinfo) == 403
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            urllib.request.urlopen(f"{base}/api/traces/{trace_ids[0]}/export?format=test-vector")
        assert _http_status(excinfo) == 403
    finally:
        inspector.stop()


async def test_chain_verify_detects_tampered_record(tmp_path: Path) -> None:
    store = tmp_path / "audit.jsonl"
    trace_ids = await _write_store(store, tamper=True)

    inspector = open_store(store, port=0)
    inspector.start_server()
    try:
        base = f"http://127.0.0.1:{inspector.server.port}"
        verdict = _get(base, "/api/chain/verify")
        assert verdict["intact"] is False
        # The edit to record 1 breaks the link *into* record 2.
        assert verdict["firstBrokenTraceId"] == trace_ids[2]
    finally:
        inspector.stop()


async def test_chain_verify_is_400_on_live_server() -> None:
    gw = _gateway()
    try:
        base = f"http://127.0.0.1:{gw.inspector.server.port}"
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            _get(base, "/api/chain/verify")
        assert _http_status(excinfo) == 400
    finally:
        gw.inspector.stop()


# ---- shared test vectors (//test-vectors/inspector/api-cases.json) ---------------


@pytest.mark.parametrize("case", _API_CASES["dagCases"], ids=lambda c: c["id"])
def test_dag_vectors(case: dict) -> None:
    dag = build_dag(case["summaries"], root=case.get("root"), session_id=case.get("sessionId"))
    expected = case["expected"]
    assert len(dag["nodes"]) == expected["nodes"]
    assert len(dag["edges"]) == expected["edges"]
    root_id = case.get("root") or next(
        n["traceId"] for n in dag["nodes"] if n["parentTraceId"] is None
    )
    root_node = next(n for n in dag["nodes"] if n["traceId"] == root_id)
    for key, value in expected["rootSubtree"].items():
        assert root_node["subtree"][key] == pytest.approx(value), key


@pytest.mark.parametrize("case", _API_CASES["replayGating"], ids=lambda c: c["mode"])
async def test_replay_gating_vectors(case: dict) -> None:
    gw = _gateway(mode=case["mode"])
    try:
        response = await gw.complete(messages=[{"role": "user", "content": "gate me"}])
        base = f"http://127.0.0.1:{gw.inspector.server.port}"
        if case["expectedStatus"] == 200:
            replayed = _post(base, "/api/replay", {"traceId": response.trace_id})
            assert replayed["traceId"]
        else:
            with pytest.raises(urllib.error.HTTPError) as excinfo:
                _post(base, "/api/replay", {"traceId": response.trace_id})
            assert _http_status(excinfo) == case["expectedStatus"]
    finally:
        gw.inspector.stop()


# ---- analytics unit coverage ------------------------------------------------------


def test_build_dag_tolerates_parent_cycles() -> None:
    summaries = [
        {"traceId": "a", "parentTraceId": "b", "status": "ok", "latencyMs": 1, "costUsd": 0.0},
        {"traceId": "b", "parentTraceId": "a", "status": "ok", "latencyMs": 1, "costUsd": 0.0},
    ]
    dag = build_dag(summaries, root="a")
    assert {n["traceId"] for n in dag["nodes"]} == {"a", "b"}


def test_build_sessions_skips_sessionless_traces() -> None:
    assert build_sessions([{"traceId": "a", "sessionId": None}]) == []
