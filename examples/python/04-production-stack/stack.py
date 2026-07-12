"""Gavio production core stack — caching, guardrails, governance, audit chain.

Composes the "Production core" interceptors in one gateway (dev mode, no
API key). Shows a semantic-cache hit skipping the provider while every other
guarantee (PII redaction, rate limiting, guardrails, tamper-evident audit) still
holds.

    pip install "gavio>=1.7.0"
    python stack.py
"""

import asyncio

from gavio import Gateway
from gavio.interceptors.audit import AuditInterceptor, verify_chain
from gavio.interceptors.cache import HashingEmbedder, SemanticCache
from gavio.interceptors.governance import RateLimiter
from gavio.interceptors.guardrails import GuardrailsInterceptor
from gavio.interceptors.guardrails.validators import RegexDenylistValidator
from gavio.interceptors.pii import PiiGuard


class _ChainSink:
    """Collects audit records so we can verify the hash chain at the end."""

    def __init__(self) -> None:
        self.records = []

    async def write(self, record) -> None:
        self.records.append(record)


async def main() -> None:
    sink = _ChainSink()
    gw = (
        Gateway.builder()
        .dev_mode(True)
        .use(AuditInterceptor(sink=sink, hash_chain=True))            # tamper-evident log
        .use(PiiGuard())                                             # redact before egress
        .use(RateLimiter(max_requests_per_minute=60))               # F-GOV-03
        .use(GuardrailsInterceptor(                                  # F-QUA-02
            validators=[RegexDenylistValidator([r"(?i)forbidden"])],
            on_failure="warn",
        ))
        .use(SemanticCache(embedder=HashingEmbedder()))             # F-CACHE-01/02, outermost policy
        .build()
    )

    msg = [{"role": "user", "content": "Summarise the account for jan@example.com"}]
    r1 = await gw.complete(messages=msg, agent_id="demo")
    r2 = await gw.complete(messages=msg, agent_id="demo")  # identical → cache hit

    print("first : cache_hit=%s" % r1.cache_hit)
    print("second: cache_hit=%s (%s)" % (r2.cache_hit, r2.cache_type.value if r2.cache_type else None))
    print("reply : ", r2.content)  # PII restored even on a cache hit
    print("fired : ", r2.interceptors_fired)
    print("pii   : ", r1.audit.pii_entity_types, " guardrail:", r1.audit.guardrail_outcome)
    print("audit chain intact:", verify_chain(sink.records))


if __name__ == "__main__":
    asyncio.run(main())
