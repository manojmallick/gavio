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
| [`pii/image-detection.json`](./pii/image-detection.json) | Image PII cases (F-SEC-09) — a stubbed `ModalityScanner` yields `ocrText` + `entityTypes`; the modality guard runs the text scanners over the OCR text, unions the direct detections, and compares the sorted entity types to `expectedTypes`. Image bytes are stubbed so the contract is deterministic across SDKs. |
| [`pii/fintech-detection.json`](./pii/fintech-detection.json) | FinTech policy pack cases — run a `PiiGuard` configured with only `fintechScanners()` over `text`, collect the sorted entity types, compare to `expectedTypes`. Exercises context-gated SWIFT/BIC and ABA routing-number checksum. |
| [`license/detection.json`](./license/detection.json) | License detection cases (F-QUA-10) — run the default license detector over `text`, collect the sorted SPDX ids, compare to `expectedLicenses`. Snippets are synthetic license text; the shipped corpus contains only shingle hashes. |
| [`inspector/cost-report.json`](./inspector/cost-report.json) | Cost Intelligence cases (F-COST-02) — run the Inspector cost-report builder over trace summaries with attribution dimensions, compare total spend, grouped spend, retry overhead, cache savings, and top-spend dimensions. |

## Case formats

`checksums.json`:
```json
{ "id": "iban-valid", "scanner": "IBAN", "text": "...NL91ABNA0417164300...", "shouldMatch": true }
```

`detection.json` (PII):
```json
{ "id": "email-and-iban", "text": "...", "expectedTypes": ["EMAIL", "IBAN"] }
```

`scanner` / entity-type names are the canonical uppercase identifiers:
`EMAIL, IBAN, BSN, CREDIT_CARD, PHONE, IP_ADDRESS, SSN, SECRET`.

`license/detection.json`:
```json
{ "id": "mit-header", "text": "...", "expectedLicenses": ["MIT"] }
```

`expectedLicenses` are SPDX ids sorted ascending:
`Apache-2.0, BSD-3-Clause, GPL-2.0, GPL-3.0, MIT, MPL-2.0`.

`inspector/cost-report.json`:
```json
{
  "id": "cost-report-groups-by-tenant",
  "groupBy": "tenant",
  "summaries": [{ "traceId": "t", "tenant": "acme", "costUsd": 0.01 }],
  "expected": { "total": { "costUsd": 0.01 } }
}
```

## Runners (one per SDK)

| SDK | Test that consumes these vectors |
|---|---|
| Python | `packages/gavio-py/tests/unit/test_vectors.py` · `packages/gavio-py/tests/unit/test_inspector_agentic.py` |
| JavaScript | `packages/gavio-js/tests/unit/test-vectors.test.ts` · `packages/gavio-js/tests/unit/inspector-api-vectors.test.ts` |
| Java | `packages/gavio-java/gavio-interceptor-pii/src/test/java/io/gavio/vectors/TestVectorsTest.java` (PII) · `packages/gavio-java/gavio-interceptor-guardrails/src/test/java/io/gavio/vectors/LicenseVectorsTest.java` (license) · `packages/gavio-java/gavio-core/src/test/java/io/gavio/inspector/InspectorApiVectorsTest.java` (Inspector) |

## Ground truth

Expected values are verified against the **Python reference implementation**.
All synthetic — no real PII appears in any vector.
