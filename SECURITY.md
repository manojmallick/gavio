# Security Policy

Gavio is security infrastructure — it guards PII, credentials, and audit trails
on the path between applications and LLM providers. We take vulnerabilities
seriously.

## Supported versions

| Version | Supported |
|---|---|
| 0.5.x | ✅ (current) |
| < 0.5 | ❌ |

Pre-1.0, security fixes land on the latest minor only.

## Reporting a vulnerability

**Do not open a public issue for security vulnerabilities.**

Report privately via GitHub's [Security Advisories](https://github.com/gavio-ai/gavio/security/advisories/new)
(preferred) or email `security@gavio.io`.

Please include:
- affected SDK(s) and version(s),
- a description and impact assessment,
- reproduction steps or a proof of concept,
- any suggested remediation.

### What to expect

- **Acknowledgement** within 3 business days.
- **Triage + severity assessment** within 7 business days.
- **Fix timeline** shared after triage. Critical fixes ship as a patch release
  within 24 hours where feasible.
- **Credit** in the advisory and `CHANGELOG.md` (opt-out available).

## Handling of sensitive data

Gavio is designed so that:
- PII is scanned and redacted **before** any provider call (F-SEC-01);
- audit records store **content hashes only** (SHA-256), never raw prompt or
  response text (F-OBS-01);
- API keys and secrets are detected by the secret scanner (F-SEC-04).

If you find a path where raw PII or credentials can reach a provider, a log, or
an audit sink unredacted, treat it as a **high-severity** report.

## Disclosure

We follow coordinated disclosure. Once a fix is released, we publish an advisory
with a CVE where applicable and a `### Security` entry in the changelog.
