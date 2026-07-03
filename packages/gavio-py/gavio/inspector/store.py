"""Read-only dashboard over a persisted audit store (F-DX-08).

``gavio inspect --store audit.jsonl`` serves the Inspector UI and JSON API in
``metadata`` mode from :class:`~gavio.interceptors.audit.sinks.jsonl.JsonlSink`
output — no running gateway, no content, no replay. The audit records seed the
same ring buffer the live server reads, so every aggregate endpoint
(``/api/dag``, ``/api/sessions``, ``/api/stats``) works unchanged, and
``/api/chain/verify`` walks the ``previous_hash`` links (F-OBS-02 surfaced).
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from .config import InspectorConfig
from .inspector import Inspector


def load_records(path: str | Path) -> list[dict[str, Any]]:
    """Parse a JSONL audit store into record dicts, skipping blank lines."""
    records = []
    for line in Path(path).expanduser().read_text(encoding="utf-8").splitlines():
        if line.strip():
            records.append(json.loads(line))
    return records


def verify_chain_records(records: list[dict[str, Any]]) -> tuple[bool, str | None]:
    """Walk previous_hash links (F-OBS-02). Returns (intact, first broken trace_id).

    Each record's ``previous_hash`` must equal the SHA-256 of the previous
    record's canonical JSON — the same digest :meth:`AuditRecord.content_hash`
    produces, recomputed here from the stored dict.
    """
    previous = ""
    for record in records:
        if record.get("previous_hash", "") != previous:
            return False, record.get("trace_id")
        canonical = json.dumps(record, sort_keys=True)
        previous = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return True, None


def summary_from_record(record: dict[str, Any]) -> dict[str, Any]:
    """Map an AuditRecord dict (snake_case) onto a trace summary (camelCase)."""
    usage = record.get("token_usage") or {}
    return {
        "traceId": record.get("trace_id"),
        "parentTraceId": record.get("parent_trace_id"),
        "agentId": record.get("agent_id"),
        "sessionId": record.get("session_id"),
        "provider": record.get("provider"),
        "model": record.get("model"),
        "wallTimeUtc": record.get("timestamp_utc"),
        # Audit records exist only for completed calls; blocked/error requests
        # never reach the audit interceptor's after-hook.
        "status": "ok",
        "latencyMs": record.get("latency_ms"),
        "costUsd": record.get("cost_usd"),
        "cacheHit": record.get("cache_hit"),
        "cacheType": record.get("cache_type"),
        "piiEntityTypes": record.get("pii_entity_types") or [],
        "interceptorsFired": record.get("interceptors_fired") or [],
        "usage": {
            "promptTokens": usage.get("prompt_tokens", 0),
            "completionTokens": usage.get("completion_tokens", 0),
            "totalTokens": usage.get("total_tokens", 0),
        },
        "promptHash": record.get("prompt_hash"),
        "responseHash": record.get("response_hash"),
        "guardrailOutcome": record.get("guardrail_outcome"),
        "riskScore": record.get("risk_score"),
    }


def open_store(
    path: str | Path,
    *,
    port: int = 7411,
    bind: str = "127.0.0.1",
    auth_token: str | None = None,
) -> Inspector:
    """Build a metadata-mode Inspector seeded from a JSONL audit store."""
    records = load_records(path)
    config = InspectorConfig(
        enabled=True, mode="metadata", port=port, bind=bind, auth_token=auth_token
    )
    config.validate(dev_mode=False)
    inspector = Inspector(
        config,
        pipeline={
            "provider": None,
            "model": None,
            "devMode": False,
            "dryRun": False,
            "store": str(path),
            "interceptors": [],
            "lints": [],
        },
    )
    inspector.audit_records = records
    for record in records:
        inspector.buffer.seed(summary_from_record(record))
    return inspector
