# 12 - Domain Policy Pack Catalog (Java)

Loads signed domain Policy Packs from the catalog, verifies signatures, applies
an override, and runs the resulting scanners through `PiiGuard.fromPolicyPack`.
**No API key, no network**.

```bash
mvn -q compile exec:java
```

Next: [Policy Packs guide](../../../docs/interceptors.md#domain-policy-packs-f-pack-010205)
