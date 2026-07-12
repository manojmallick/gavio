# Gavio Stability Policy

Gavio 1.0.0 is the stable release gate for the multi-SDK runtime. The gate does
not introduce a new interceptor; it confirms that the public surface, docs,
benchmarks, security policy, and release automation are ready for long-term use.

## API stability guarantee

Starting with `1.0.0`, Gavio follows Semantic Versioning for the public API:

- Patch releases include backward-compatible bug and security fixes.
- Minor releases include backward-compatible features and documented additions.
- Major releases are required for breaking changes to public APIs.

The public API includes:

- documented Python imports from `gavio` and `gavio.interceptors.*`;
- documented JavaScript package exports from `gavio` and subpath exports;
- documented Java artifacts, packages, builders, request/response types, and
  interceptors;
- JSON schemas in `spec/`;
- shared cross-SDK behavior in `test-vectors/`;
- documented configuration and Inspector HTTP endpoints.

Internal helpers, private modules, generated build output, and undocumented test
fixtures are not part of the stability guarantee.

## Deprecation policy

Breaking changes after `1.0.0` require a deprecation window before removal:

- announce the replacement in docs and `CHANGELOG.md`;
- keep the deprecated API for at least one minor release where practical;
- include migration notes when removal is planned;
- remove only in the next major release unless a security fix requires faster
  action.

## Long-term support

The `1.x` line receives Long-term support for 24 months from the `1.0.0`
release date: 2026-07-12 through 2028-07-12.

- critical security fixes target a patch release within 24 hours where feasible;
- high-severity security fixes target the next patch release;
- compatibility fixes prioritize supported Python, Node.js, and Java versions;
- backports are limited to the latest supported `1.x` minor unless a security
  advisory calls out a wider impact.

## Stable release gate

Before any stable tag is published, maintainers must run:

```bash
python3 scripts/stable_release_gate.py --version 2.2.0
```

The release workflow runs the same gate with the tag name:

```bash
python3 scripts/stable_release_gate.py --tag "$GITHUB_REF_NAME"
```

The gate checks:

- Python, JavaScript, Java, package-lock, and runtime-exported SDK versions stay
  in lockstep;
- `CHANGELOG.md` has the current release section and compare links;
- release, security, stability, docs-site, benchmark, and workflow documents are
  present;
- CI and tag release workflows run the gate before publishing;
- the JavaScript package has no publish-time install hooks;
- the Python core keeps zero mandatory runtime dependencies.

## Release evidence

The stable release is considered ready only when these evidence items are
published or linked:

- all CI jobs pass across supported Python, Node.js, and Java versions;
- Inspector benchmark thresholds pass and the latest numbers are recorded in
  `benchmarks/inspector/README.md`;
- `SECURITY.md` names the supported line and reporting process;
- docs build and deploy successfully;
- package registries publish the same version for PyPI, npm, Maven Central, and
  GitHub Packages.
