# 03 · Custom PII scanner

Detect a domain-specific identifier (an ING bank account) by implementing the
`PiiScanner` interface, compose it with a built-in scanner, then unit-test it in
isolation with `GavioTestKit` — **no API key, no network**.

```bash
pip install -r requirements.txt
python custom_scanner.py
```

Shows `EMAIL` + `ING_ACCOUNT` detected and restored, then an isolated test
asserting the custom entity is redacted before the provider and restored after.

See also: [writing a custom scanner](../../../docs/interceptors.md#writing-a-custom-scanner)
