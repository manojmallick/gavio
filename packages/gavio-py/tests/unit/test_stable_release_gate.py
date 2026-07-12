from __future__ import annotations

import importlib.util
import shutil
import sys
from pathlib import Path


def _repo_root() -> Path:
    for parent in Path(__file__).resolve().parents:
        if (parent / "scripts" / "stable_release_gate.py").is_file():
            return parent
    raise AssertionError("repository root not found")


REPO_ROOT = _repo_root()
GATE_PATH = REPO_ROOT / "scripts" / "stable_release_gate.py"


def _load_gate():
    spec = importlib.util.spec_from_file_location("stable_release_gate", GATE_PATH)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def _copy_gate_fixture(dst: Path) -> None:
    files = [
        "CHANGELOG.md",
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
        "packages/gavio-py/pyproject.toml",
        "packages/gavio-py/gavio/__init__.py",
        "packages/gavio-js/package.json",
        "packages/gavio-js/package-lock.json",
        "packages/gavio-js/src/version.ts",
    ]
    java_poms = (REPO_ROOT / "packages/gavio-java").rglob("pom.xml")
    files.extend(str(path.relative_to(REPO_ROOT)) for path in java_poms)

    for relative in files:
        source = REPO_ROOT / relative
        target = dst / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)


def test_stable_release_gate_passes_current_repo():
    gate = _load_gate()

    result = gate.run_gate(REPO_ROOT, expected_version="2.0.0")

    assert result.failures == ()
    assert result.version == "2.0.0"


def test_stable_release_gate_reports_python_runtime_version_drift(tmp_path: Path):
    gate = _load_gate()
    _copy_gate_fixture(tmp_path)
    init_path = tmp_path / "packages/gavio-py/gavio/__init__.py"
    init_path.write_text(
        init_path.read_text(encoding="utf-8").replace(
            '__version__ = "2.0.0"', '__version__ = "0.14.0"'
        ),
        encoding="utf-8",
    )

    result = gate.run_gate(tmp_path, expected_version="2.0.0")

    assert any("Python runtime __version__" in failure for failure in result.failures)
