# Contributing to Gavio

Thanks for your interest in Gavio — the open standard AI gateway. Contributions
of all kinds are welcome: bug reports, features, docs, and test vectors.

## Ground rules

Gavio ships three SDKs — Python, Java, JavaScript — **in lockstep**. Every SDK
ships the same features at the same version. When you change behaviour in one
SDK, the same behaviour must land in the others (or be tracked as a follow-up
issue), and the shared [test vectors](./test-vectors/) must still pass in all
three.

## Repository layout

| Path | What |
|---|---|
| [`spec/`](./spec/) | Canonical data model (JSON Schema) — the source of truth |
| [`test-vectors/`](./test-vectors/) | Shared cases every SDK must pass |
| `packages/gavio-py/` | Python SDK (reference implementation) |
| `packages/gavio-js/` | JavaScript / TypeScript SDK |
| `packages/gavio-java/` | Java SDK (Maven multi-module) |
| [`docs/`](./docs/) | Architecture, guides, per-package docs |
| [`CHANGELOG.md`](./CHANGELOG.md) | Keep a Changelog format + feature IDs (`F-*`) |

## Development

```bash
# Python
cd packages/gavio-py && python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]" && pytest tests/unit -q && ruff check gavio tests

# JavaScript
cd packages/gavio-js && npm ci && npm run typecheck && npm test

# Java
cd packages/gavio-java && mvn test
```

## Pull request requirements

Every PR must:

1. **Reference a feature ID** (e.g. `Implements F-SEC-01`) — the `F-*` IDs used
   throughout [`CHANGELOG.md`](./CHANGELOG.md).
2. **Update [`CHANGELOG.md`](./CHANGELOG.md)** under `[Unreleased]`, referencing
   the feature ID.
3. **Pass CI across all three SDK test suites**, including the shared test
   vectors.
4. **Include tests** for the new behaviour. Prefer adding to
   [`test-vectors/`](./test-vectors/) so all three SDKs are covered at once.
5. **Update or add documentation** (SDK README, `spec/`, or `docs/`).

## Commit messages

Conventional-commit style: `type: description` (lowercase, present tense).
Valid types: `feat | fix | test | docs | chore | refactor | perf`.

```
feat: add query expander for deep search pipeline
fix: iban scanner rejected valid Belgian IBANs
test: add IPv6 compression cases to shared vectors
```

Breaking changes are prefixed `⚠️ BREAKING` in the changelog and require a
deprecation period of at least one minor version (semver — pre-1.0, APIs may
change until v0.2.0).

## Code of conduct

This project follows the [Contributor Covenant 2.1](./CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the
project's [MIT License](./LICENSE).
