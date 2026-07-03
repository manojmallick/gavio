# 04 · Production core stack

Composes the "Production core" interceptors — **hash-chain audit → PII
guard → rate limiter → guardrails → semantic cache** — in one gateway (dev mode,
no API key).

```bash
pip install -r requirements.txt
python stack.py
```

The second identical call is a **semantic-cache hit** that skips the provider,
yet PII is still restored, guardrails still validate the (cached) output, and the
audit hash-chain still verifies. Shows how the executor policies (cache,
guardrails) compose around the provider while the pre/post interceptors (audit,
PII, rate limit) wrap the whole thing.

See also: [interceptors guide](../../../docs/interceptors.md)
