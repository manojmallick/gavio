# Releasing Gavio

All three SDKs release **in lockstep** from a single version tag. Pushing a tag
like `v0.1.0` triggers [`.github/workflows/release.yml`](.github/workflows/release.yml),
which tests, verifies the manifest version matches the tag, and publishes to
**PyPI**, **Maven Central**, and **npm**, then creates one GitHub Release.

You only need to do the one-time setup (§1–2) once. After that, releasing is §3.

---

## 1. One-time prerequisites

### Package names / namespace must be yours

| Ecosystem | Name in manifest | Must be available/owned |
|---|---|---|
| PyPI | `gavio` | the project name `gavio` on pypi.org |
| npm | `gavio` | the package name `gavio` on npmjs.com |
| Maven Central | groupId `io.github.manojmallick` | your GitHub account (auto-verified) |

> **Maven Central namespace.** The groupId is **`io.github.manojmallick`**, which
> Sonatype Central auto-verifies against your GitHub account — no domain needed.
> (The Java package in source stays `io.gavio.*`; groupId and package don't have
> to match.) To register it: sign in at central.sonatype.com → *Namespaces* →
> *Add Namespace* → `io.github.manojmallick` → follow the GitHub verification
> (it has you create a public repo whose name is the verification code).

> If `gavio` is already taken on npm or PyPI, use a scope/owner prefix
> (`@manojmallick/gavio`, or a different PyPI name) — tell me and I'll update the
> manifests.

### Accounts

- **PyPI** — account at https://pypi.org (enable 2FA).
- **Sonatype Central Portal** — account at https://central.sonatype.com, with the
  `io.github.manojmallick` namespace registered + verified (GitHub auto-verify).
- **npm** — account at https://npmjs.com (already set up).

---

## 2. Repository secrets

Add these under **GitHub repo → Settings → Secrets and variables → Actions**, or
with the `gh` CLI (examples below).

| Secret | What it is | Where to get it |
|---|---|---|
| `PYPI_TOKEN` | PyPI API token (`pypi-…`) | pypi.org → Account → API tokens → *Add* (scope to project `gavio` after first upload, or account-wide for the first) |
| `MAVEN_CENTRAL_USERNAME` | Central Portal token **username** | central.sonatype.com → *View Account* → *Generate User Token* |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token **password** | same dialog as above |
| `GPG_PRIVATE_KEY` | ASCII-armored private key used to sign jars | generate below |
| `GPG_PASSPHRASE` | passphrase for that key | you choose it at key-gen |
| `NPM_TOKEN` | npm **Automation** token | npmjs.com → Access Tokens → *Generate* → Automation (already added ✓) |

### Generate the GPG signing key (Java only)

Maven Central requires every artifact to be GPG-signed and the **public** key to
be on a public keyserver.

```bash
# 1. Generate a key (RSA 4096, pick a passphrase — that's GPG_PASSPHRASE)
gpg --full-generate-key

# 2. Find its long key id
gpg --list-secret-keys --keyid-format=long
#   sec   rsa4096/ABCD1234EF567890 2026-07-01 ...
#                   ^^^^^^^^^^^^^^^^ this is <KEYID>

# 3. Publish the PUBLIC key (Central checks this)
gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>

# 4. Export the PRIVATE key → this whole block is the GPG_PRIVATE_KEY secret
gpg --armor --export-secret-keys <KEYID>
```

### Add secrets with the gh CLI

```bash
cd /Users/manojmallick/Downloads/gavio

gh secret set PYPI_TOKEN                      # paste the pypi-… token
gh secret set MAVEN_CENTRAL_USERNAME          # paste the token username
gh secret set MAVEN_CENTRAL_PASSWORD          # paste the token password
gh secret set GPG_PASSPHRASE                  # paste your passphrase
gh secret set GPG_PRIVATE_KEY < private-key.asc   # from `gpg --armor --export-secret-keys`
# NPM_TOKEN already set:
gh secret list
```

---

## 3. Cutting a release

1. **Bump the version** in all three manifests so they match the tag:
   - `packages/gavio-py/pyproject.toml` → `version`
   - `packages/gavio-js/package.json` → `version`
   - `packages/gavio-java/pom.xml` (parent) → `<version>` (and it flows to modules)
   - `packages/gavio-py/gavio/__init__.py` → `__version__`
2. Move `CHANGELOG.md` `[Unreleased]` → the new version with today's date.
3. Commit, then tag and push:
   ```bash
   git commit -am "chore: release v0.1.1"
   git tag -a v0.1.1 -m "Gavio v0.1.1"
   git push origin main --follow-tags
   ```
4. Watch it: `gh run watch` (or the Actions tab). The workflow fails fast if any
   manifest version ≠ the tag.

> The version-match guard means a mismatched tag/manifest can't publish — so a
> bad tag is safe to delete and re-push after fixing the manifest.

---

## 4. First-publish notes

- **PyPI / npm:** the very first publish must use an **account-scoped** token
  (the project doesn't exist yet to scope to). After the first release, rotate to
  a project-scoped token and update `PYPI_TOKEN`.
- **Maven Central:** the first deploy to a namespace can take a little longer to
  propagate to `search.maven.org` (up to ~30 min) even after the workflow goes
  green. `autoPublish=true` means no manual "release" click in the portal.
- **Provenance:** npm and PyPI publishes attach build provenance (via the
  `id-token: write` permission), so no extra config is needed.
