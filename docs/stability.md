# Stability

Gavio `1.0.0` is the stable release gate for the Python, JavaScript, and Java
SDKs. It confirms API compatibility, release hygiene, benchmark evidence,
security posture, and docs readiness before the first stable tag ships.

## API stability

After `1.0.0`, public APIs follow Semantic Versioning:

| Release | Compatibility promise |
|---|---|
| Patch | backward-compatible bug and security fixes |
| Minor | backward-compatible features and documented additions |
| Major | breaking changes |

The stable surface includes documented SDK imports/exports, Java artifacts,
request/response models, JSON schemas in `spec/`, shared test vectors,
documented configuration, and Inspector endpoints.

## LTS

The `1.x` line receives LTS coverage from 2026-07-12 through 2028-07-12.
Critical security fixes target patch releases within 24 hours where
feasible, and compatibility fixes prioritize the supported Python, Node.js, and
Java versions exercised by CI.

## Stable release gate

Maintainers run the stable release gate before publishing:

```bash
python3 scripts/stable_release_gate.py --version 1.1.0
```

The tag workflow runs the same check with the pushed tag:

```bash
python3 scripts/stable_release_gate.py --tag "$GITHUB_REF_NAME"
```

The gate checks lockstep SDK versions, changelog links, release docs, security
and stability docs, benchmark evidence, workflow wiring, package hygiene, and
zero mandatory Python core dependencies.

See [`STABILITY.md`](../STABILITY.md) for the full policy.
