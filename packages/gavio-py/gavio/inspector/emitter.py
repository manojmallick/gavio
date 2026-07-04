"""TraceEmitter — per-request event emission for the Inspector (F-DX-09).

One emitter per gateway call. It owns the trace clock (monotonic nanoseconds
since trace.start), the per-trace ``seq`` counter, and the capture-mode
branch: in ``metadata`` mode only the content-free constructors from
:mod:`gavio.inspector.events` are ever called.
"""

from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Any

from ..context import InterceptorContext
from ..interceptors.chain import Executor
from ..request import GavioRequest
from ..response import GavioResponse
from . import events
from .bus import InspectorBus


class TraceEmitter:
    """Emits InspectorEvents for one request onto the bus."""

    def __init__(self, bus: InspectorBus, mode: str, trace_id: str) -> None:
        self._bus = bus
        self.mode = mode
        self._trace_id = trace_id
        self._t0 = time.perf_counter_ns()
        self._seq = 0
        self._attempt = 0
        # Set when a provider call or interceptor hook raises, read by trace_error.
        self._error_origin: str = "chain"
        self._error_interceptor: str | None = None

    # ---- envelope ------------------------------------------------------------

    def _emit(self, type_: str, data: dict[str, Any]) -> None:
        t_ns = time.perf_counter_ns() - self._t0
        self._bus.emit(events.envelope(self._trace_id, type_, t_ns, self._seq, data))
        self._seq += 1

    # ---- trace lifecycle -------------------------------------------------------

    def trace_start(self, request: GavioRequest) -> None:
        common = {
            "provider": request.provider.value,
            "model": request.model,
            "wall_time_utc": datetime.now(timezone.utc).isoformat(),
            "mode": self.mode,
            "parent_trace_id": request.parent_trace_id,
            "agent_id": request.agent_id,
            "session_id": request.session_id,
        }
        if self.mode == "metadata":
            data = events.trace_start_data(**common)
        else:
            data = events.trace_start_data_with_content(**common, messages=request.messages)
        self._emit("trace.start", data)

    def trace_end(self, response: GavioResponse, ctx: InterceptorContext) -> None:
        common = {
            "status": "ok",
            "latency_ms": self._elapsed_ms(),
            "interceptors_fired": list(ctx.interceptors_fired),
            "cost_usd": response.cost_usd,
            "cache_hit": response.cache_hit,
            "cache_type": response.cache_type.value if response.cache_type else None,
            "pii_entity_types": list(ctx.pii_entity_types),
        }
        if self.mode == "metadata":
            data = events.trace_end_data(**common)
        else:
            data = events.trace_end_data_with_content(**common, content=response.content)
        self._emit("trace.end", data)

    def trace_error(self, error: Exception) -> None:
        self._emit(
            "trace.error",
            events.trace_error_data(
                origin=self._error_origin,
                error_type=type(error).__name__,
                message=str(error),
                handled=False,
                interceptor_name=self._error_interceptor,
            ),
        )

    def trace_end_error(self, ctx: InterceptorContext) -> None:
        self._emit(
            "trace.end",
            events.trace_end_data(
                status="error",
                latency_ms=self._elapsed_ms(),
                interceptors_fired=list(ctx.interceptors_fired),
                pii_entity_types=list(ctx.pii_entity_types),
            ),
        )

    # ---- interceptor hooks -------------------------------------------------------

    def interceptor_start(self, phase: str, name: str) -> None:
        self._emit(f"interceptor.{phase}.start", events.interceptor_start_data(name))

    def note_error(self, origin: str, interceptor_name: str | None) -> None:
        """Record where a raised exception originated for the trace.error event."""
        self._error_origin = origin
        self._error_interceptor = interceptor_name

    def interceptor_before_end(
        self,
        name: str,
        started_ns: int,
        old: GavioRequest,
        new: GavioRequest,
        ctx: InterceptorContext,
    ) -> None:
        duration_us = (time.perf_counter_ns() - started_ns) // 1000
        mutated = old.messages != new.messages or old.model != new.model
        decision = self._drain_decision(name, ctx)
        if self.mode == "metadata":
            data = events.interceptor_end_data(name, duration_us, mutated, decision)
        else:
            diff = None
            if mutated:
                diff = events.request_diff(
                    old.messages,
                    new.messages,
                    old.model,
                    new.model,
                    include_from=self.mode == "full",
                )
            data = events.interceptor_end_data_with_diff(name, duration_us, mutated, decision, diff)
        self._emit("interceptor.before.end", data)
        self._emit_governance(ctx)

    def interceptor_after_end(
        self,
        name: str,
        started_ns: int,
        old: GavioResponse,
        new: GavioResponse,
        ctx: InterceptorContext,
    ) -> None:
        duration_us = (time.perf_counter_ns() - started_ns) // 1000
        mutated = old.content != new.content
        decision = self._drain_decision(name, ctx)
        if self.mode == "metadata":
            data = events.interceptor_end_data(name, duration_us, mutated, decision)
        else:
            diff = None
            if mutated:
                diff = events.content_diff(
                    old.content, new.content, include_from=self.mode == "full"
                )
            data = events.interceptor_end_data_with_diff(name, duration_us, mutated, decision, diff)
        self._emit("interceptor.after.end", data)
        self._emit_governance(ctx)

    def _emit_governance(self, ctx: InterceptorContext) -> None:
        """Emit a standalone governance.event for each queued alert (F-GOV-07)."""
        for data in ctx.drain_governance():
            self._emit("governance.event", data)

    @staticmethod
    def _drain_decision(name: str, ctx: InterceptorContext) -> dict[str, Any] | None:
        pending = ctx.drain_inspect()
        if pending:
            return pending
        if name in ctx.state:
            entry = ctx.state[name]
            return entry if isinstance(entry, dict) else {name: entry}
        return None

    # ---- provider call --------------------------------------------------------------

    def wrap_provider_call(self, provider_name: str, inner: Executor) -> Executor:
        """Wrap the innermost executor with provider.call.start/end events."""

        async def wrapped(request: GavioRequest) -> GavioResponse:
            self._attempt += 1
            self._emit(
                "provider.call.start",
                events.provider_call_start_data(provider_name, request.model, self._attempt),
            )
            started = time.perf_counter_ns()
            try:
                response = await inner(request)
            except Exception as error:
                duration_us = (time.perf_counter_ns() - started) // 1000
                self._emit(
                    "provider.call.end",
                    events.provider_call_end_data(
                        duration_us, "error", error_type=type(error).__name__
                    ),
                )
                self.note_error("provider", None)
                raise
            duration_us = (time.perf_counter_ns() - started) // 1000
            self._emit(
                "provider.call.end",
                events.provider_call_end_data(
                    duration_us,
                    "ok",
                    model_version=response.model_version or None,
                    usage=response.usage,
                ),
            )
            return response

        return wrapped

    def _elapsed_ms(self) -> int:
        return (time.perf_counter_ns() - self._t0) // 1_000_000
