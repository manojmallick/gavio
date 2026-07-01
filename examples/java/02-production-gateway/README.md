# 02 · Production gateway (Java)

A realistic interceptor stack — **audit → PII guard → timeout → retry** — in
front of a real provider. Falls back to `MockProvider` when no key is set.

```bash
export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY  (optional)
mvn -q compile exec:java
```

`AuditInterceptor` is registered first (outermost); reliability policies wrap the
provider call. `interceptorsFired` → `[audit, pii_guard, timeout, retry]`.

Next: [03 · custom-scanner](../03-custom-scanner/)
