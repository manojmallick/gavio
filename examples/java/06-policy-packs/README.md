# 06 - Policy Packs (Java)

Shows built-in and custom Policy Packs: core PII, FinTech identifiers, and a
custom employee-id regex rule. **No API key, no network**.

```bash
mvn -q compile exec:java
```

The example prints pack manifest metadata, redacts identifiers before the mock
provider, restores them in the response, and shows the detected entity types.

Next: [07 - tool-runtime](../07-tool-runtime/) -
[Interceptors guide](../../../docs/interceptors.md#domain-policy-packs-f-pack-010205)
