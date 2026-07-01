# 01 · Quickstart (Java)

The smallest Gavio program. Dev mode runs everything in-process (mock provider +
stdout audit) — **no API key, no network**.

```bash
mvn -q compile exec:java
```

You'll see the email + IBAN redacted before the mock provider, restored in the
reply, and `PII found: [EMAIL, IBAN]`.

> Note: `GavioResponse.audit()` is typed `Object` (core avoids depending on the
> audit module), so cast it: `AuditRecord audit = (AuditRecord) r.audit();`.

Next: [02 · production-gateway](../02-production-gateway/) ·
[Java guide](../../../docs/packages/java.md)
