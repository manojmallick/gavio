# Gavio Test Vectors

Shared, language-agnostic test cases that **every SDK must pass**. They turn
cross-SDK parity from a manual promise into a runnable contract: each SDK loads
these JSON files in its own test suite and asserts the same results.

If a change to detection logic, checksums, or redaction breaks parity, the
offending SDK's test-vector run goes red.

## Files

| File | What it checks |
|---|---|
| [`pii/checksums.json`](./pii/checksums.json) | Single-scanner cases — regex + checksum logic (IBAN mod-97, BSN 11-proef, Luhn, IP validation). Each case: run the named scanner over `text`, assert `matchCount > 0 === shouldMatch`. |
| [`pii/detection.json`](./pii/detection.json) | Full-pipeline cases — run the default `PiiGuard` over `text`, collect unique entity types, sort, compare to `expectedTypes`. Exercises the whole scanner set plus overlap resolution. |

## Case formats

`checksums.json`:
```json
{ "id": "iban-valid", "scanner": "IBAN", "text": "...NL91ABNA0417164300...", "shouldMatch": true }
```

`detection.json`:
```json
{ "id": "email-and-iban", "text": "...", "expectedTypes": ["EMAIL", "IBAN"] }
```

`scanner` / entity-type names are the canonical uppercase identifiers:
`EMAIL, IBAN, BSN, CREDIT_CARD, PHONE, IP_ADDRESS, SSN, SECRET`.

## Runners (one per SDK)

| SDK | Test that consumes these vectors |
|---|---|
| Python | `packages/gavio-py/tests/unit/test_vectors.py` |
| JavaScript | `packages/gavio-js/tests/unit/test-vectors.test.ts` |
| Java | `packages/gavio-java/gavio-interceptor-pii/src/test/java/io/gavio/vectors/TestVectorsTest.java` |

## Ground truth

Expected values are verified against the **Python reference implementation**.
All synthetic — no real PII appears in any vector.
