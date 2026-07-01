"""StdoutSink — human-readable audit output for development (F-OBS-05)."""

from __future__ import annotations

import json
import sys

from ..record import AuditRecord
from ..sink import AuditSink


class StdoutSink(AuditSink):
    """Print each audit record to stdout. Zero dependencies."""

    def __init__(self, pretty: bool = True, stream=None) -> None:
        self.pretty = pretty
        self._stream = stream if stream is not None else sys.stdout

    async def write(self, record: AuditRecord) -> None:
        data = record.to_dict()
        if self.pretty:
            line = self._format_pretty(data)
        else:
            line = json.dumps(data)
        print(line, file=self._stream, flush=True)

    @staticmethod
    def _format_pretty(data: dict) -> str:
        usage = data["token_usage"]
        pii = data["pii_entity_types"] or ["none"]
        return (
            "[gavio:audit] "
            f"trace={data['trace_id'][:18]}… "
            f"{data['provider']}/{data['model']} "
            f"tokens={usage['total_tokens']} "
            f"cost=${data['cost_usd']:.6f} "
            f"latency={data['latency_ms']}ms "
            f"cache={'HIT' if data['cache_hit'] else 'miss'} "
            f"pii={','.join(pii)} "
            f"interceptors=[{','.join(data['interceptors_fired'])}]"
        )
