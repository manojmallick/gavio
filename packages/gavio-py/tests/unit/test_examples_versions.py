from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path


def _repo_root() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "examples" / "README.md").is_file():
            return parent
    raise AssertionError("repository root not found")


REPO_ROOT = _repo_root()
VERSION_RE = re.compile(r'^version\s*=\s*"([^"]+)"', re.MULTILINE)


def _canonical_version() -> str:
    pyproject = (REPO_ROOT / "packages/gavio-py/pyproject.toml").read_text(
        encoding="utf-8"
    )
    match = VERSION_RE.search(pyproject)
    assert match is not None
    return match.group(1)


EXPECTED_VERSION = _canonical_version()


def test_python_example_requirements_use_current_gavio_version():
    for requirements in sorted((REPO_ROOT / "examples/python").glob("*/requirements.txt")):
        assert requirements.read_text(encoding="utf-8").strip() == (
            f"gavio>={EXPECTED_VERSION}"
        ), requirements


def test_javascript_example_manifests_and_locks_use_current_gavio_version():
    for package_json in sorted((REPO_ROOT / "examples/javascript").glob("*/package.json")):
        package_dir = package_json.parent
        package_data = json.loads(package_json.read_text(encoding="utf-8"))

        assert package_data["dependencies"]["gavio"] == f"^{EXPECTED_VERSION}", package_json

        lock_data = json.loads((package_dir / "package-lock.json").read_text(encoding="utf-8"))
        root_package = lock_data["packages"][""]
        gavio_package = lock_data["packages"]["node_modules/gavio"]

        assert root_package["dependencies"]["gavio"] == f"^{EXPECTED_VERSION}", package_dir
        assert gavio_package["version"] == EXPECTED_VERSION, package_dir
        assert gavio_package["resolved"].endswith(f"/gavio-{EXPECTED_VERSION}.tgz"), package_dir


def test_java_example_poms_use_current_gavio_version():
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    for pom in sorted((REPO_ROOT / "examples/java").glob("*/pom.xml")):
        root = ET.parse(pom).getroot()
        for dependency in root.findall("m:dependencies/m:dependency", namespace):
            group_id = dependency.findtext("m:groupId", namespaces=namespace)
            artifact_id = dependency.findtext("m:artifactId", namespaces=namespace)
            version = dependency.findtext("m:version", namespaces=namespace)
            if group_id == "io.github.manojmallick" and artifact_id.startswith("gavio-"):
                assert version == EXPECTED_VERSION, f"{pom}: {artifact_id}"


def test_examples_readme_names_current_published_version():
    readme = (REPO_ROOT / "examples/README.md").read_text(encoding="utf-8")

    assert f"published `{EXPECTED_VERSION}` packages" in readme
