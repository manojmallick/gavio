"""AuditInterceptor (F-OBS-01) — captures a full record of every call."""

from __future__ import annotations

import asyncio
import logging

from ...context import InterceptorContext
from ...request import GavioRequest
from ...response import GavioResponse
from ..base import Interceptor
from .record import AuditRecord
from .sink import AuditSink
from .sinks.stdout import StdoutSink

logger = logging.getLogger("gavio.audit")

_PROMPT_HASH_KEY = "audit_prompt_hash"


class AuditInterceptor(Interceptor):
    """Build an :class:`AuditRecord` per request and write it to a sink.

    Register this as the outermost interceptor so its ``after`` runs last and
    sees the final, fully-processed response. It hashes the (already PII-
    redacted) prompt in ``before`` and the response in ``after`` — content is
    never stored, only digests and metadata.

    With ``hash_chain=True`` (F-OBS-02) each record's ``previous_hash`` is set to
    the SHA-256 of the previous record, forming a tamper-evident chain that
    :func:`gavio.interceptors.audit.verify_chain` can validate.
    """

    def __init__(
        self, sink: AuditSink | str | None = None, hash_chain: bool = False
    ) -> None:
        self.sink = _resolve_sink(sink)
        self.hash_chain = hash_chain
        self._last_hash = ""
        self._chain_lock = asyncio.Lock()

    @property
    def name(self) -> str:
        return "audit"

    @property
    def dry_run_safe(self) -> bool:
        # Auditing is observation-only, so it always runs.
        return True

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        ctx.state[_PROMPT_HASH_KEY] = AuditRecord.hash_text(request.prompt_text())
        return request

    async def after(
        self, response: GavioResponse, ctx: InterceptorContext
    ) -> GavioResponse:
        record = AuditRecord(
            trace_id=response.trace_id,
            parent_trace_id=ctx.parent_trace_id,
            agent_id=ctx.agent_id,
            session_id=ctx.session_id,
            timestamp_utc=AuditRecord.now_utc(),
            provider=response.provider,
            model=response.model,
            model_version=response.model_version,
            prompt_hash=ctx.state.get(_PROMPT_HASH_KEY, ""),
            response_hash=AuditRecord.hash_text(response.content),
            token_usage=response.usage,
            cost_usd=response.cost_usd,
            latency_ms=response.latency_ms,
            pii_entity_types=list(ctx.pii_entity_types),
            pii_entity_counts=dict(ctx.pii_entity_counts),
            interceptors_fired=list(ctx.interceptors_fired),
            cache_hit=response.cache_hit,
            cache_type=response.cache_type.value if response.cache_type else None,
            guardrail_outcome=ctx.guardrail_outcome,
            risk_score=ctx.risk_score,
        )
        if self.hash_chain:
            async with self._chain_lock:
                record.previous_hash = self._last_hash
                self._last_hash = record.content_hash()
        response.audit = record
        try:
            await self.sink.write(record)
        except Exception:  # noqa: BLE001 - auditing must never break the call
            logger.exception("audit sink write failed for trace %s", record.trace_id)
        return response


def _resolve_sink(sink: AuditSink | str | None) -> AuditSink:
    if sink is None:
        return StdoutSink()
    if isinstance(sink, str):
        if sink in ("stdout://", "stdout"):
            return StdoutSink()
        raise ValueError(f"Unknown audit sink: {sink!r}")
    return sink
