# 03 · Custom PII scanner (Java)

Implement `PiiScanner` to detect a domain-specific identifier (an ING account),
compose it with a built-in scanner, then unit-test it with `GavioTestKit`.
**No API key, no network.**

```bash
mvn -q compile exec:java
```

Shows `EMAIL` + `ING_ACCOUNT` detected and restored, then an isolated test
asserting the custom entity is redacted before the provider.

See also: [writing a custom scanner](../../../docs/interceptors.md#writing-a-custom-scanner)
