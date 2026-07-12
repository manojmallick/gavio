# 12 - Domain Policy Pack Catalog (Python)

Loads signed domain Policy Packs from the catalog, verifies signatures, applies
an override, and runs the resulting scanners through `PiiGuard`. **No API key,
no network**.

```bash
pip install -r requirements.txt
python domain_policy_packs.py
```

Next: [Policy Packs guide](../../../docs/interceptors.md#domain-policy-packs-f-pack-010205)
