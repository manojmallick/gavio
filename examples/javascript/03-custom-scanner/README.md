# 03 · Custom PII scanner (JavaScript)

A `PiiScanner` in JavaScript is just an object — `{ entityType, scan(text, ctx) }`.
No classes, no TypeScript. This detects an ING bank account, composes it with a
built-in scanner, then unit-tests it with `GavioTestKit`. **No API key, no network.**

```bash
npm install
node custom-scanner.mjs
```

Shows `EMAIL` + `ING_ACCOUNT` detected and restored, then an isolated test that
asserts the custom entity is redacted before the provider and restored after
(`result.piiDetected('ING_ACCOUNT')`, `result.preRequestText()`,
`result.response.content`).

See also: [writing a custom scanner](../../../docs/interceptors.md#writing-a-custom-scanner)
