# 02 · Production gateway (JavaScript)

A realistic interceptor stack — **audit → PII guard → timeout → retry** — in
front of a real provider, in plain JavaScript (ESM). Falls back to the mock
provider when no key is set, so it always runs.

```bash
npm install
export ANTHROPIC_API_KEY=sk-ant-...   # or OPENAI_API_KEY  (optional)
node gateway.mjs
```

`auditInterceptor` is registered first so it's **outermost** (its record captures
the final, PII-redacted result); reliability policies wrap the provider call.
`interceptorsFired` shows `[audit, pii_guard, timeout, retry]`.

Next: [03 · custom-scanner](../03-custom-scanner/)
