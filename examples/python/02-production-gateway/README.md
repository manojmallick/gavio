# 02 · Production gateway

A realistic interceptor stack — **audit → PII guard → timeout → retry** — in
front of a real provider. Falls back to the mock provider when no key is set, so
it always runs.

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY  (optional)
python gateway.py
```

Note the ordering: `AuditInterceptor` is registered first so it's **outermost**
(its record captures the final, PII-redacted result); reliability policies wrap
the provider call. `interceptors_fired` shows `[audit, pii_guard, timeout, retry]`.

Next: [03 · custom-scanner](../03-custom-scanner/)
