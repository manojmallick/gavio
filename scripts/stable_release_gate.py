#!/usr/bin/env python3
"""Validate Gavio's stable-release prerequisites.

The gate is intentionally dependency-free so it can run before any SDK-specific
install step in CI and release workflows.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SEMVER_RE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")
PYPROJECT_VERSION_RE = re.compile(r'^version\s*=\s*"([^"]+)"', re.MULTILINE)
PY_RUNTIME_VERSION_RE = re.compile(r'^__version__\s*=\s*"([^"]+)"', re.MULTILINE)
TS_VERSION_RE = re.compile(r"export\s+const\s+VERSION\s*=\s*['\"]([^'\"]+)['\"]")


@dataclass(frozen=True)
class VersionFact:
    label: str
    path: str
    version: str


@dataclass(frozen=True)
class GateResult:
    version: str
    version_facts: tuple[VersionFact, ...]
    failures: tuple[str, ...]


def _read_text(root: Path, relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise FileNotFoundError(relative)
    return path.read_text(encoding="utf-8")


def _load_json(root: Path, relative: str) -> dict[str, Any]:
    return json.loads(_read_text(root, relative))


def _regex_version(root: Path, relative: str, pattern: re.Pattern[str], label: str) -> VersionFact:
    text = _read_text(root, relative)
    match = pattern.search(text)
    if not match:
        raise ValueError(f"{relative} does not expose {label}")
    return VersionFact(label=label, path=relative, version=match.group(1))


def _xml_child_text(node: ET.Element, local_name: str) -> str | None:
    for child in list(node):
        if child.tag.rsplit("}", 1)[-1] == local_name and child.text:
            return child.text.strip()
    return None


def _pom_version(path: Path) -> str:
    root = ET.parse(path).getroot()
    version = _xml_child_text(root, "version")
    if version:
        return version
    for child in list(root):
        if child.tag.rsplit("}", 1)[-1] == "parent":
            parent_version = _xml_child_text(child, "version")
            if parent_version:
                return parent_version
    raise ValueError(f"{path} does not declare or inherit a version")


def collect_version_facts(root: Path) -> tuple[VersionFact, ...]:
    facts: list[VersionFact] = []

    facts.append(
        _regex_version(
            root,
            "packages/gavio-py/pyproject.toml",
            PYPROJECT_VERSION_RE,
            "Python pyproject version",
        )
    )
    facts.append(
        _regex_version(
            root,
            "packages/gavio-py/gavio/__init__.py",
            PY_RUNTIME_VERSION_RE,
            "Python runtime __version__",
        )
    )

    package_json = _load_json(root, "packages/gavio-js/package.json")
    facts.append(
        VersionFact(
            label="JavaScript package.json version",
            path="packages/gavio-js/package.json",
            version=str(package_json["version"]),
        )
    )

    package_lock = _load_json(root, "packages/gavio-js/package-lock.json")
    facts.append(
        VersionFact(
            label="JavaScript package-lock root version",
            path="packages/gavio-js/package-lock.json",
            version=str(package_lock["version"]),
        )
    )
    lock_package = package_lock.get("packages", {}).get("", {})
    facts.append(
        VersionFact(
            label="JavaScript package-lock package version",
            path="packages/gavio-js/package-lock.json",
            version=str(lock_package.get("version", "")),
        )
    )
    facts.append(
        _regex_version(
            root,
            "packages/gavio-js/src/version.ts",
            TS_VERSION_RE,
            "JavaScript runtime VERSION",
        )
    )

    for pom in sorted((root / "packages/gavio-java").rglob("pom.xml")):
        facts.append(
            VersionFact(
                label="Java POM version",
                path=str(pom.relative_to(root)),
                version=_pom_version(pom),
            )
        )

    return tuple(facts)


def _check_versions(
    root: Path, expected_version: str | None, failures: list[str]
) -> tuple[VersionFact, ...]:
    facts = collect_version_facts(root)
    versions = {fact.version for fact in facts}
    if len(versions) != 1:
        details = "\n".join(
            f"  - {fact.label}: {fact.version} ({fact.path})" for fact in facts
        )
        failures.append("SDK versions are not in lockstep:\n" + details)

    actual = facts[0].version if facts else ""
    if expected_version and versions != {expected_version}:
        failures.append(
            f"Expected every SDK version to be {expected_version}, found: "
            + ", ".join(sorted(versions))
        )
    elif actual and not SEMVER_RE.match(actual):
        failures.append(f"Version {actual!r} is not SemVer MAJOR.MINOR.PATCH")

    return facts


def _require_contains(
    root: Path, relative: str, needles: tuple[str, ...], failures: list[str]
) -> None:
    try:
        text = _read_text(root, relative)
    except FileNotFoundError:
        failures.append(f"Missing required file: {relative}")
        return
    missing = [needle for needle in needles if needle not in text]
    if missing:
        failures.append(f"{relative} is missing required text: {', '.join(missing)}")


def _check_changelog(root: Path, version: str, failures: list[str]) -> None:
    try:
        changelog = _read_text(root, "CHANGELOG.md")
    except FileNotFoundError:
        failures.append("Missing CHANGELOG.md")
        return

    if "## [Unreleased]" not in changelog:
        failures.append("CHANGELOG.md is missing an [Unreleased] section")
    unreleased_link = (
        f"[Unreleased]: https://github.com/manojmallick/gavio/compare/v{version}...HEAD"
    )
    if unreleased_link not in changelog:
        failures.append(f"CHANGELOG.md [Unreleased] link must compare v{version}...HEAD")
    if f"## [{version}]" not in changelog:
        failures.append(f"CHANGELOG.md is missing a [{version}] release section")
    if f"[{version}]: https://github.com/manojmallick/gavio/" not in changelog:
        failures.append(f"CHANGELOG.md is missing a [{version}] compare/release link")


def _check_docs(root: Path, version: str, failures: list[str]) -> None:
    required_files = (
        "README.md",
        "RELEASING.md",
        "SECURITY.md",
        "STABILITY.md",
        "benchmarks/inspector/README.md",
        "docs/README.md",
        "docs/stability.md",
        "docs-vp/.vitepress/config.mts",
        "docs-vp/guide/stability.md",
        ".github/workflows/ci.yml",
        ".github/workflows/release.yml",
        ".github/workflows/release-ghp.yml",
    )
    for relative in required_files:
        if not (root / relative).is_file():
            failures.append(f"Missing required file: {relative}")

    _require_contains(
        root,
        "STABILITY.md",
        (
            "API stability guarantee",
            "Long-term support",
            "24 months",
            "Stable release gate",
        ),
        failures,
    )
    _require_contains(root, "README.md", ("STABILITY.md", "Stable release gate"), failures)
    _require_contains(root, "docs/README.md", ("stability.md", "stable release gate"), failures)
    _require_contains(
        root,
        "docs/stability.md",
        ("Stable release gate", "API stability", "LTS"),
        failures,
    )
    _require_contains(
        root,
        "RELEASING.md",
        ("scripts/stable_release_gate.py", "stable release gate"),
        failures,
    )
    _require_contains(
        root,
        "SECURITY.md",
        ("1.x", "24 months", "latest minor"),
        failures,
    )
    _require_contains(
        root,
        "benchmarks/inspector/README.md",
        ("CI threshold", "Latest local release-prep check"),
        failures,
    )
    _require_contains(
        root,
        "docs-vp/.vitepress/config.mts",
        ("/guide/stability", f"v{version}"),
        failures,
    )
    _require_contains(
        root,
        "docs-vp/guide/stability.md",
        ("Stable release gate", "API stability", "LTS"),
        failures,
    )


def _check_workflows(root: Path, failures: list[str]) -> None:
    _require_contains(
        root,
        ".github/workflows/ci.yml",
        ("scripts/stable_release_gate.py",),
        failures,
    )
    _require_contains(
        root,
        ".github/workflows/release.yml",
        ("stable-release-gate", 'scripts/stable_release_gate.py --tag "$GITHUB_REF_NAME"'),
        failures,
    )
    _require_contains(
        root,
        ".github/workflows/release-ghp.yml",
        ('scripts/stable_release_gate.py --tag "$GITHUB_REF_NAME"',),
        failures,
    )


def _check_package_hygiene(root: Path, failures: list[str]) -> None:
    try:
        pyproject = _read_text(root, "packages/gavio-py/pyproject.toml")
    except FileNotFoundError:
        failures.append("Missing packages/gavio-py/pyproject.toml")
        return
    if not re.search(r"^dependencies\s*=\s*\[\s*\]\s*$", pyproject, re.MULTILINE):
        failures.append("Python core must keep zero mandatory dependencies")

    try:
        package_json = _load_json(root, "packages/gavio-js/package.json")
    except FileNotFoundError:
        failures.append("Missing packages/gavio-js/package.json")
        return
    scripts = package_json.get("scripts", {})
    install_hooks = {"preinstall", "install", "postinstall"}
    found_hooks = sorted(install_hooks.intersection(scripts))
    if found_hooks:
        failures.append(
            "JavaScript package must not publish install hooks: " + ", ".join(found_hooks)
        )
    files = package_json.get("files", [])
    disallowed = sorted(item for item in files if item in {"node_modules", ".env", "test", "tests"})
    if disallowed:
        failures.append(
            "JavaScript package files include disallowed entries: " + ", ".join(disallowed)
        )


def run_gate(
    root: Path, expected_version: str | None = None, expected_tag: str | None = None
) -> GateResult:
    root = root.resolve()
    failures: list[str] = []

    if expected_tag:
        if not expected_tag.startswith("v"):
            failures.append(f"Expected tag must start with 'v', got {expected_tag!r}")
        else:
            expected_version = expected_tag[1:]

    if expected_version and not SEMVER_RE.match(expected_version):
        failures.append(f"Expected version {expected_version!r} is not SemVer MAJOR.MINOR.PATCH")

    try:
        facts = _check_versions(root, expected_version, failures)
    except (FileNotFoundError, KeyError, ValueError, ET.ParseError) as exc:
        failures.append(str(exc))
        facts = ()

    version = expected_version or (facts[0].version if facts else "")
    if version:
        _check_changelog(root, version, failures)
        _check_docs(root, version, failures)
    _check_workflows(root, failures)
    _check_package_hygiene(root, failures)

    return GateResult(version=version, version_facts=facts, failures=tuple(failures))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--version", help="Expected SDK version, e.g. 1.0.0")
    parser.add_argument("--tag", help="Expected release tag, e.g. v1.0.0")
    args = parser.parse_args(argv)

    result = run_gate(args.root, expected_version=args.version, expected_tag=args.tag)
    if result.failures:
        print("Stable release gate failed:", file=sys.stderr)
        for failure in result.failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(f"Stable release gate passed for v{result.version}")
    for fact in result.version_facts:
        print(f"- {fact.label}: {fact.version} ({fact.path})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
