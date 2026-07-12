"""Prompt Registry + Evals foundation.

The registry renders versioned chat templates into Gavio messages and attaches
existing PromptLineage metadata. Eval reports are metadata-safe by default:
they store output hashes and assertion details, not raw model output.
"""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import inspect
import json
import re
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING, Any

from ..request import GavioRequest
from ..types import Message, PromptLineage, Provider

_PLACEHOLDER = re.compile(r"{{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*}}")
_SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$"
)
PROMPT_MANIFEST_SCHEMA_VERSION = "gavio.prompt-registry.v2"
PROMPT_MANIFEST_SIGNATURE_ALGORITHM = "HMAC-SHA256"


@dataclass(frozen=True)
class PromptApproval:
    """Human approval metadata for a prompt template version."""

    status: str
    approved_by: str | None = None
    approved_at: str | None = None
    reviewers: tuple[str, ...] = ()
    reason: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> PromptApproval:
        return cls(
            status=str(data["status"]),
            approved_by=_optional_str(data.get("approvedBy", data.get("approved_by"))),
            approved_at=_optional_str(data.get("approvedAt", data.get("approved_at"))),
            reviewers=tuple(str(item) for item in data.get("reviewers", ())),
            reason=_optional_str(data.get("reason")),
            metadata=dict(data.get("metadata") or {}),
        )

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "status": self.status,
            "reviewers": list(self.reviewers),
        }
        if self.approved_by is not None:
            data["approvedBy"] = self.approved_by
        if self.approved_at is not None:
            data["approvedAt"] = self.approved_at
        if self.reason is not None:
            data["reason"] = self.reason
        if self.metadata:
            data["metadata"] = dict(self.metadata)
        return data


@dataclass(frozen=True)
class PromptDiffChange:
    """Metadata-safe description of one prompt-template difference."""

    path: str
    type: str
    before_hash: str | None = None
    after_hash: str | None = None
    before: Any = None
    after: Any = None

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {"path": self.path, "type": self.type}
        if self.before_hash is not None:
            data["beforeHash"] = self.before_hash
        if self.after_hash is not None:
            data["afterHash"] = self.after_hash
        if self.before is not None:
            data["before"] = self.before
        if self.after is not None:
            data["after"] = self.after
        return data


@dataclass(frozen=True)
class PromptDiff:
    """Prompt-template diff that hashes message text instead of exposing it."""

    from_id: str
    from_version: str
    to_id: str
    to_version: str
    changes: tuple[PromptDiffChange, ...]

    @property
    def has_changes(self) -> bool:
        return bool(self.changes)

    def to_dict(self) -> dict[str, Any]:
        return {
            "from": {"id": self.from_id, "version": self.from_version},
            "to": {"id": self.to_id, "version": self.to_version},
            "hasChanges": self.has_changes,
            "changes": [change.to_dict() for change in self.changes],
        }


@dataclass(frozen=True)
class RenderedPrompt:
    """Rendered messages plus metadata-only prompt lineage."""

    messages: list[Message]
    lineage: PromptLineage

    def to_request(
        self,
        *,
        model: str,
        provider: Provider | str = Provider.MOCK,
        **kwargs: Any,
    ) -> GavioRequest:
        return GavioRequest(
            messages=[dict(message) for message in self.messages],
            model=model,
            provider=Provider.coerce(provider),
            lineage=self.lineage,
            **kwargs,
        )


@dataclass(frozen=True)
class PromptTemplate:
    """Versioned chat prompt template."""

    id: str
    version: str
    messages: list[Message]
    required_variables: tuple[str, ...] = ()
    metadata: dict[str, Any] = field(default_factory=dict)
    approval: PromptApproval | None = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> PromptTemplate:
        approval = data.get("approval", data.get("approvalMetadata"))
        return cls(
            id=str(data["id"]),
            version=str(data["version"]),
            messages=[dict(message) for message in data["messages"]],
            required_variables=tuple(
                str(v)
                for v in data.get("requiredVariables", data.get("required_variables", ()))
            ),
            metadata=dict(data.get("metadata") or {}),
            approval=PromptApproval.from_dict(approval) if isinstance(approval, dict) else None,
        )

    def to_dict(self) -> dict[str, Any]:
        data = {
            "id": self.id,
            "version": self.version,
            "messages": [dict(message) for message in self.messages],
            "requiredVariables": list(self.required_variables),
            "metadata": dict(self.metadata),
        }
        if self.approval is not None:
            data["approval"] = self.approval.to_dict()
        return data

    def placeholders(self) -> set[str]:
        found: set[str] = set()
        for message in self.messages:
            for value in message.values():
                if isinstance(value, str):
                    found.update(_PLACEHOLDER.findall(value))
        return found

    def render(self, variables: dict[str, Any]) -> RenderedPrompt:
        missing = sorted((set(self.required_variables) | self.placeholders()) - set(variables))
        if missing:
            raise ValueError(
                f"prompt template {self.id}@{self.version} missing variables: {missing}"
            )
        rendered = [
            {key: _render_value(value, variables) for key, value in message.items()}
            for message in self.messages
        ]
        lineage = PromptLineage(
            template_id=self.id,
            template_version=self.version,
            variables=dict(variables),
            rag_chunks=[],
        )
        return RenderedPrompt(messages=rendered, lineage=lineage)

    def diff(self, other: PromptTemplate) -> PromptDiff:
        return diff_prompt_templates(self, other)


class PromptRegistry:
    """Registry for versioned prompt templates.

    Templates can be registered in memory or loaded from a prompt manifest file.
    Semver template versions resolve by highest semantic version; legacy
    non-semver versions keep the previous registration-order behavior.
    """

    def __init__(self, templates: Iterable[PromptTemplate | dict[str, Any]] = ()) -> None:
        self._templates: dict[tuple[str, str], PromptTemplate] = {}
        self._latest: dict[str, str] = {}
        for template in templates:
            self.register(template)

    def register(self, template: PromptTemplate | dict[str, Any]) -> PromptTemplate:
        parsed = (
            template
            if isinstance(template, PromptTemplate)
            else PromptTemplate.from_dict(template)
        )
        key = (parsed.id, parsed.version)
        self._templates[key] = parsed
        self._latest[parsed.id] = self._resolve_latest_after_register(parsed.id, parsed.version)
        return parsed

    def get(self, template_id: str, version: str | None = None) -> PromptTemplate:
        resolved = self._resolve_version(template_id, version)
        if resolved is None or (template_id, resolved) not in self._templates:
            raise KeyError(f"prompt template not found: {template_id}@{version or 'latest'}")
        return self._templates[(template_id, resolved)]

    def render(
        self,
        template_id: str,
        variables: dict[str, Any],
        *,
        version: str | None = None,
    ) -> RenderedPrompt:
        return self.get(template_id, version).render(variables)

    def versions(self, template_id: str) -> tuple[str, ...]:
        versions = [version for item_id, version in self._templates if item_id == template_id]
        return tuple(sorted(versions, key=_version_sort_key))

    def diff(
        self,
        template_id: str,
        from_version: str,
        to_version: str | None = None,
    ) -> PromptDiff:
        return self.get(template_id, from_version).diff(self.get(template_id, to_version))

    @classmethod
    def from_manifest(
        cls,
        data: dict[str, Any],
        *,
        verify_secret: str | bytes | None = None,
        validate_semver: bool | None = None,
    ) -> PromptRegistry:
        if verify_secret is not None and not verify_prompt_manifest_signature(data, verify_secret):
            raise ValueError("prompt manifest signature verification failed")
        require_semver = (
            validate_semver
            if validate_semver is not None
            else data.get("schemaVersion") == PROMPT_MANIFEST_SCHEMA_VERSION
        )
        registry = cls()
        for raw_template in data.get("templates", ()):
            template = PromptTemplate.from_dict(dict(raw_template))
            if require_semver:
                validate_semantic_version(template.version)
            registry.register(template)
        return registry

    @classmethod
    def from_file(
        cls,
        path: str | Path,
        *,
        verify_secret: str | bytes | None = None,
        validate_semver: bool | None = None,
    ) -> PromptRegistry:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls.from_manifest(
            data,
            verify_secret=verify_secret,
            validate_semver=validate_semver,
        )

    def to_manifest(
        self,
        *,
        registry_id: str = "default",
        metadata: dict[str, Any] | None = None,
        sign_secret: str | bytes | None = None,
        key_id: str = "local",
    ) -> dict[str, Any]:
        manifest: dict[str, Any] = {
            "schemaVersion": PROMPT_MANIFEST_SCHEMA_VERSION,
            "registryId": registry_id,
            "metadata": dict(metadata or {}),
            "templates": [
                self._templates[key].to_dict()
                for key in sorted(
                    self._templates,
                    key=lambda item: (item[0], _version_sort_key(item[1])),
                )
            ],
        }
        if sign_secret is not None:
            return sign_prompt_manifest(manifest, sign_secret, key_id=key_id)
        return manifest

    def _resolve_version(self, template_id: str, selector: str | None) -> str | None:
        if selector is None or selector == "latest":
            return self._latest.get(template_id)
        if (template_id, selector) in self._templates:
            return selector
        candidates = [
            version
            for item_id, version in self._templates
            if item_id == template_id and _parse_semver(version) is not None
        ]
        for candidate in sorted(candidates, key=_version_sort_key, reverse=True):
            if _matches_semver_selector(candidate, selector):
                return candidate
        return selector

    def _resolve_latest_after_register(self, template_id: str, registered: str) -> str:
        versions = [version for item_id, version in self._templates if item_id == template_id]
        semver_versions = [version for version in versions if _parse_semver(version) is not None]
        if len(semver_versions) == len(versions):
            return max(semver_versions, key=_version_sort_key)
        return registered


@dataclass(frozen=True)
class EvalAssertionResult:
    type: str
    passed: bool
    expected: Any
    reason: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "type": self.type,
            "passed": self.passed,
            "expected": self.expected,
            "reason": self.reason,
        }


@dataclass(frozen=True)
class EvalAssertion:
    """Simple built-in output assertion."""

    type: str
    value: Any
    case_sensitive: bool = False

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> EvalAssertion:
        return cls(
            type=str(data["type"]),
            value=data.get("value"),
            case_sensitive=bool(data.get("caseSensitive", data.get("case_sensitive", False))),
        )

    def check(self, output: str) -> EvalAssertionResult:
        expected = self.value
        if self.type == "regex":
            passed = re.search(str(expected), output) is not None
        elif self.type == "equals":
            passed = _matches(output, str(expected), self.case_sensitive)
        elif self.type == "not_contains":
            passed = _needle(str(expected), self.case_sensitive) not in _haystack(
                output, self.case_sensitive
            )
        elif self.type == "contains":
            passed = _needle(str(expected), self.case_sensitive) in _haystack(
                output, self.case_sensitive
            )
        else:
            raise ValueError(f"unsupported eval assertion type: {self.type}")
        reason = "passed" if passed else f"{self.type} assertion failed"
        return EvalAssertionResult(self.type, passed, expected, reason)


@dataclass(frozen=True)
class EvalCase:
    id: str
    template_id: str
    variables: dict[str, Any]
    assertions: tuple[EvalAssertion, ...]
    template_version: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)
    triage: EvalFailureTriage | None = None

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> EvalCase:
        metadata = dict(data.get("metadata") or {})
        triage = data.get("triage")
        if triage is None and isinstance(metadata.get("triage"), dict):
            triage = metadata["triage"]
        return cls(
            id=str(data["id"]),
            template_id=str(data["templateId"]),
            template_version=data.get("templateVersion"),
            variables=dict(data.get("variables") or {}),
            assertions=tuple(EvalAssertion.from_dict(item) for item in data.get("assertions", ())),
            metadata=_sanitize_workflow_metadata(metadata),
            triage=EvalFailureTriage.from_dict(triage) if isinstance(triage, dict) else None,
        )


@dataclass(frozen=True)
class EvalFailureTriage:
    """Metadata for routing a failed eval case without storing model output."""

    category: str | None = None
    severity: str | None = None
    owner: str | None = None
    action: str | None = None
    notes: str | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> EvalFailureTriage:
        return cls(
            category=_optional_str(data.get("category")),
            severity=_optional_str(data.get("severity")),
            owner=_optional_str(data.get("owner")),
            action=_optional_str(data.get("action")),
            notes=_optional_str(data.get("notes")),
            metadata=_sanitize_workflow_metadata(dict(data.get("metadata") or {})),
        )

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {}
        if self.category is not None:
            data["category"] = self.category
        if self.severity is not None:
            data["severity"] = self.severity
        if self.owner is not None:
            data["owner"] = self.owner
        if self.action is not None:
            data["action"] = self.action
        if self.notes is not None:
            data["notes"] = self.notes
        if self.metadata:
            data["metadata"] = _sanitize_workflow_metadata(self.metadata)
        return data


@dataclass(frozen=True)
class EvalCaseResult:
    id: str
    template_id: str
    template_version: str
    passed: bool
    score: float
    output_hash: str
    assertions: tuple[EvalAssertionResult, ...]
    lineage: PromptLineage
    triage: EvalFailureTriage | None = None

    def to_dict(self) -> dict[str, Any]:
        data = {
            "id": self.id,
            "templateId": self.template_id,
            "templateVersion": self.template_version,
            "passed": self.passed,
            "score": self.score,
            "outputHash": self.output_hash,
            "assertions": [result.to_dict() for result in self.assertions],
            "lineage": _lineage_to_camel(self.lineage),
        }
        if self.triage is not None:
            data["triage"] = self.triage.to_dict()
        return data


@dataclass(frozen=True)
class EvalReport:
    suite_id: str
    cases: tuple[EvalCaseResult, ...]

    @property
    def total_cases(self) -> int:
        return len(self.cases)

    @property
    def passed_cases(self) -> int:
        return sum(1 for case in self.cases if case.passed)

    @property
    def failed_cases(self) -> int:
        return self.total_cases - self.passed_cases

    @property
    def score(self) -> float:
        if not self.cases:
            return 0.0
        return _round8(sum(case.score for case in self.cases) / len(self.cases))

    def to_dict(self) -> dict[str, Any]:
        return {
            "suiteId": self.suite_id,
            "totalCases": self.total_cases,
            "passedCases": self.passed_cases,
            "failedCases": self.failed_cases,
            "score": self.score,
            "cases": [case.to_dict() for case in self.cases],
        }


if TYPE_CHECKING:
    CompletionFn = Callable[[RenderedPrompt, EvalCase], str | Awaitable[str]]
else:
    CompletionFn = Callable[..., object]


@dataclass(frozen=True)
class EvalSuite:
    id: str
    cases: tuple[EvalCase, ...]

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> EvalSuite:
        return cls(
            id=str(data["id"]),
            cases=tuple(EvalCase.from_dict(item) for item in data.get("cases", ())),
        )

    async def run(self, registry: PromptRegistry, complete: CompletionFn) -> EvalReport:
        results = []
        for case in self.cases:
            rendered = registry.render(
                case.template_id,
                case.variables,
                version=case.template_version,
            )
            output_or_awaitable = complete(rendered, case)
            output = (
                await output_or_awaitable
                if inspect.isawaitable(output_or_awaitable)
                else output_or_awaitable
            )
            assertion_results = tuple(assertion.check(str(output)) for assertion in case.assertions)
            passed = all(result.passed for result in assertion_results)
            score = (
                _round8(
                    sum(1 for result in assertion_results if result.passed)
                    / len(assertion_results)
                )
                if assertion_results
                else 0.0
            )
            results.append(
                EvalCaseResult(
                    id=case.id,
                    template_id=case.template_id,
                    template_version=rendered.lineage.template_version or "",
                    passed=passed,
                    score=score,
                    output_hash=hashlib.sha256(str(output).encode("utf-8")).hexdigest(),
                    assertions=assertion_results,
                    lineage=rendered.lineage,
                    triage=case.triage if not passed else None,
                )
            )
        return EvalReport(self.id, tuple(results))

    def run_sync(self, registry: PromptRegistry, complete: CompletionFn) -> EvalReport:
        return asyncio.run(self.run(registry, complete))


@dataclass(frozen=True)
class PromptEvalLink:
    """Connect one prompt version to the eval suite that gates it."""

    prompt_id: str
    prompt_version: str
    suite_id: str
    baseline_score: float | None = None
    fail_under: float | None = None
    max_regression: float = 0.0
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(
        cls,
        data: dict[str, Any],
        *,
        prompt_id: str | None = None,
        prompt_version: str | None = None,
        suite_id: str | None = None,
    ) -> PromptEvalLink:
        raw_prompt_id = data.get("promptId", data.get("templateId", prompt_id))
        raw_prompt_version = data.get(
            "promptVersion",
            data.get("templateVersion", prompt_version),
        )
        raw_suite_id = data.get("suiteId", data.get("evalSuiteId", data.get("id", suite_id)))
        if raw_prompt_id is None or raw_prompt_version is None or raw_suite_id is None:
            raise ValueError("prompt eval link requires promptId, promptVersion, and suiteId")
        baseline = data.get("baselineScore", data.get("baseline_score"))
        fail_under = data.get("failUnder", data.get("fail_under"))
        max_regression = data.get("maxRegression", data.get("max_regression", 0.0))
        return cls(
            prompt_id=str(raw_prompt_id),
            prompt_version=str(raw_prompt_version),
            suite_id=str(raw_suite_id),
            baseline_score=float(baseline) if baseline is not None else None,
            fail_under=float(fail_under) if fail_under is not None else None,
            max_regression=float(max_regression),
            metadata=_sanitize_workflow_metadata(dict(data.get("metadata") or {})),
        )

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "promptId": self.prompt_id,
            "promptVersion": self.prompt_version,
            "suiteId": self.suite_id,
            "maxRegression": self.max_regression,
        }
        if self.baseline_score is not None:
            data["baselineScore"] = self.baseline_score
        if self.fail_under is not None:
            data["failUnder"] = self.fail_under
        if self.metadata:
            data["metadata"] = _sanitize_workflow_metadata(self.metadata)
        return data


@dataclass(frozen=True)
class PromptVersionGate:
    """Per-prompt-version eval gate result."""

    prompt_id: str
    prompt_version: str
    suite_id: str
    passed: bool
    score: float
    total_cases: int
    passed_cases: int
    failed_cases: tuple[str, ...]
    reasons: tuple[str, ...]
    baseline_score: float | None = None
    fail_under: float | None = None
    max_regression: float = 0.0
    score_delta: float | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "promptId": self.prompt_id,
            "promptVersion": self.prompt_version,
            "suiteId": self.suite_id,
            "passed": self.passed,
            "score": self.score,
            "totalCases": self.total_cases,
            "passedCases": self.passed_cases,
            "failedCases": list(self.failed_cases),
            "reasons": list(self.reasons),
            "baselineScore": self.baseline_score,
            "failUnder": self.fail_under,
            "maxRegression": self.max_regression,
            "scoreDelta": self.score_delta,
        }


@dataclass(frozen=True)
class PromptWorkflowResult:
    links: tuple[PromptEvalLink, ...]
    gates: tuple[PromptVersionGate, ...]

    @property
    def passed(self) -> bool:
        return all(gate.passed for gate in self.gates)

    def to_dict(self) -> dict[str, Any]:
        return {
            "passed": self.passed,
            "links": [link.to_dict() for link in self.links],
            "gates": [gate.to_dict() for gate in self.gates],
        }


@dataclass(frozen=True)
class PromptReleaseBundle:
    """Metadata-safe prompt release evidence bundle."""

    bundle_id: str
    prompt_id: str
    prompt_version: str
    generated_at: str
    manifest_identity: dict[str, Any]
    gates: tuple[PromptVersionGate, ...]
    reports: tuple[EvalReport, ...]
    prompt_diff: PromptDiff | None = None
    metadata: dict[str, Any] = field(default_factory=dict)

    @property
    def passed(self) -> bool:
        return all(gate.passed for gate in self.gates)

    def to_dict(self) -> dict[str, Any]:
        data: dict[str, Any] = {
            "schemaVersion": "gavio.prompt-release-bundle.v1",
            "bundleId": self.bundle_id,
            "prompt": {"id": self.prompt_id, "version": self.prompt_version},
            "generatedAt": self.generated_at,
            "manifest": dict(self.manifest_identity),
            "passed": self.passed,
            "gates": [gate.to_dict() for gate in self.gates],
            "evalReports": [report.to_dict() for report in self.reports],
        }
        if self.prompt_diff is not None:
            data["promptDiff"] = self.prompt_diff.to_dict()
        if self.metadata:
            data["metadata"] = _sanitize_workflow_metadata(self.metadata)
        return data


def evaluate_prompt_version_gate(report: EvalReport, link: PromptEvalLink) -> PromptVersionGate:
    """Evaluate one linked prompt version against one eval report."""

    if report.suite_id != link.suite_id:
        matched_cases: tuple[EvalCaseResult, ...] = ()
    else:
        matched_cases = tuple(
            case
            for case in report.cases
            if case.template_id == link.prompt_id and case.template_version == link.prompt_version
        )
    reasons: list[str] = []
    failed = tuple(case.id for case in matched_cases if not case.passed)
    if not matched_cases:
        reasons.append(
            f"no eval cases found for {link.prompt_id}@{link.prompt_version} in {link.suite_id}"
        )
        score = 0.0
    else:
        score = _round8(sum(case.score for case in matched_cases) / len(matched_cases))
    if failed:
        reasons.append(f"{len(failed)} linked eval case(s) failed")
    if link.fail_under is not None and score < link.fail_under:
        reasons.append(f"score {score:.8f} is below fail-under {link.fail_under:.8f}")
    score_delta: float | None = None
    if link.baseline_score is not None:
        score_delta = _round8(score - link.baseline_score)
        if score_delta < -link.max_regression:
            reasons.append(
                "score regression "
                f"{score_delta:.8f} exceeds allowed {link.max_regression:.8f}"
            )
    return PromptVersionGate(
        prompt_id=link.prompt_id,
        prompt_version=link.prompt_version,
        suite_id=link.suite_id,
        passed=not reasons,
        score=score,
        total_cases=len(matched_cases),
        passed_cases=sum(1 for case in matched_cases if case.passed),
        failed_cases=failed,
        reasons=tuple(reasons),
        baseline_score=link.baseline_score,
        fail_under=link.fail_under,
        max_regression=link.max_regression,
        score_delta=score_delta,
    )


def evaluate_prompt_workflow(
    report: EvalReport,
    links: Iterable[PromptEvalLink | dict[str, Any]],
) -> PromptWorkflowResult:
    parsed_links = tuple(
        link if isinstance(link, PromptEvalLink) else PromptEvalLink.from_dict(link)
        for link in links
    )
    return PromptWorkflowResult(
        links=parsed_links,
        gates=tuple(evaluate_prompt_version_gate(report, link) for link in parsed_links),
    )


def prompt_eval_links_from_manifest(manifest: dict[str, Any]) -> tuple[PromptEvalLink, ...]:
    """Load prompt/eval links from a manifest-level or template-level field."""

    links: list[PromptEvalLink] = []
    for raw in manifest.get("promptEvalLinks", manifest.get("evalLinks", ())):
        if isinstance(raw, dict):
            links.append(PromptEvalLink.from_dict(raw))
    for raw_template in manifest.get("templates", ()):
        if not isinstance(raw_template, dict):
            continue
        prompt_id = str(raw_template.get("id"))
        prompt_version = str(raw_template.get("version"))
        candidates: list[Any] = []
        for key in ("promptEvalLinks", "evalLinks", "evals"):
            value = raw_template.get(key)
            if isinstance(value, list):
                candidates.extend(value)
        metadata = raw_template.get("metadata")
        if isinstance(metadata, dict):
            for key in ("promptEvalLinks", "evalLinks", "evals"):
                value = metadata.get(key)
                if isinstance(value, list):
                    candidates.extend(value)
        for raw in candidates:
            if isinstance(raw, dict):
                links.append(
                    PromptEvalLink.from_dict(
                        raw,
                        prompt_id=prompt_id,
                        prompt_version=prompt_version,
                    )
                )
    return tuple(links)


def build_prompt_release_bundle(
    *,
    manifest: dict[str, Any],
    prompt_id: str,
    prompt_version: str,
    reports: Iterable[EvalReport],
    links: Iterable[PromptEvalLink | dict[str, Any]] | None = None,
    from_version: str | None = None,
    generated_at: str | None = None,
    bundle_id: str | None = None,
    metadata: dict[str, Any] | None = None,
) -> PromptReleaseBundle:
    """Build metadata-safe evidence for releasing one prompt version."""

    registry = PromptRegistry.from_manifest(manifest, validate_semver=False)
    target = registry.get(prompt_id, prompt_version)
    prompt_diff: PromptDiff | None = None
    if from_version is None:
        previous = [
            version
            for version in registry.versions(prompt_id)
            if version != target.version
        ]
        if previous:
            from_version = previous[-1]
    if from_version is not None:
        prompt_diff = registry.diff(prompt_id, from_version, target.version)
    parsed_reports = tuple(reports)
    source_links = links if links is not None else prompt_eval_links_from_manifest(manifest)
    parsed_links = tuple(
        link
        for link in _parse_prompt_eval_links(source_links)
        if link.prompt_id == prompt_id and link.prompt_version == prompt_version
    )
    gates = tuple(
        evaluate_prompt_version_gate(report, link)
        for report in parsed_reports
        for link in parsed_links
        if link.suite_id == report.suite_id
    )
    return PromptReleaseBundle(
        bundle_id=bundle_id or f"{prompt_id}@{prompt_version}",
        prompt_id=prompt_id,
        prompt_version=prompt_version,
        generated_at=generated_at or datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        manifest_identity=_manifest_identity(manifest),
        gates=gates,
        reports=parsed_reports,
        prompt_diff=prompt_diff,
        metadata=_sanitize_workflow_metadata(dict(metadata or {})),
    )


def _parse_prompt_eval_links(
    links: Iterable[PromptEvalLink | dict[str, Any]],
) -> tuple[PromptEvalLink, ...]:
    return tuple(
        link if isinstance(link, PromptEvalLink) else PromptEvalLink.from_dict(link)
        for link in links
    )


def _render_value(value: Any, variables: dict[str, Any]) -> Any:
    if not isinstance(value, str):
        return value
    return _PLACEHOLDER.sub(lambda match: str(variables[match.group(1)]), value)


def _matches(left: str, right: str, case_sensitive: bool) -> bool:
    left_cmp = left if case_sensitive else left.lower()
    right_cmp = right if case_sensitive else right.lower()
    return left_cmp == right_cmp


def _haystack(value: str, case_sensitive: bool) -> str:
    return value if case_sensitive else value.lower()


def _needle(value: str, case_sensitive: bool) -> str:
    return value if case_sensitive else value.lower()


def _round8(value: float) -> float:
    return round(value, 8)


def _lineage_to_camel(lineage: PromptLineage) -> dict[str, Any]:
    return {
        "templateId": lineage.template_id,
        "templateVersion": lineage.template_version,
        "variables": dict(lineage.variables),
        "ragChunks": [chunk.to_dict() for chunk in lineage.rag_chunks],
    }


_CONTENT_METADATA_KEYS = {
    "content",
    "completion",
    "messages",
    "output",
    "prompt",
    "raw",
    "rawOutput",
    "renderedPrompt",
    "response",
    "text",
}


def _sanitize_workflow_metadata(data: dict[str, Any]) -> dict[str, Any]:
    sanitized: dict[str, Any] = {}
    for key, value in data.items():
        if key in _CONTENT_METADATA_KEYS:
            sanitized[f"{key}Hash"] = _sha256_json(value)
            continue
        sanitized[key] = _sanitize_workflow_value(value)
    return sanitized


def _sanitize_workflow_value(value: Any) -> Any:
    if isinstance(value, dict):
        return _sanitize_workflow_metadata(value)
    if isinstance(value, list):
        return [_sanitize_workflow_value(item) for item in value]
    return value


def _manifest_identity(manifest: dict[str, Any]) -> dict[str, Any]:
    identity: dict[str, Any] = {
        "schemaVersion": manifest.get("schemaVersion"),
        "registryId": manifest.get("registryId"),
        "digest": prompt_manifest_digest(manifest),
    }
    signature = manifest.get("signature")
    if isinstance(signature, dict):
        identity["signature"] = {
            key: signature[key]
            for key in ("algorithm", "keyId", "value")
            if key in signature
        }
    return identity


def diff_prompt_templates(before: PromptTemplate, after: PromptTemplate) -> PromptDiff:
    """Return a metadata-safe diff between two prompt template versions."""

    changes: list[PromptDiffChange] = []
    max_messages = max(len(before.messages), len(after.messages))
    for idx in range(max_messages):
        path = f"messages[{idx}]"
        if idx >= len(before.messages):
            changes.append(
                PromptDiffChange(
                    path=path,
                    type="added",
                    after_hash=_sha256_json(after.messages[idx]),
                )
            )
            continue
        if idx >= len(after.messages):
            changes.append(
                PromptDiffChange(
                    path=path,
                    type="removed",
                    before_hash=_sha256_json(before.messages[idx]),
                )
            )
            continue
        before_message = before.messages[idx]
        after_message = after.messages[idx]
        for key in sorted(set(before_message) | set(after_message)):
            if before_message.get(key) != after_message.get(key):
                changes.append(
                    PromptDiffChange(
                        path=f"{path}.{key}",
                        type=_change_type(key in before_message, key in after_message),
                        before_hash=_sha256_json(before_message.get(key))
                        if key in before_message
                        else None,
                        after_hash=_sha256_json(after_message.get(key))
                        if key in after_message
                        else None,
                    )
                )

    before_required = set(before.required_variables)
    after_required = set(after.required_variables)
    if before_required != after_required:
        changes.append(
            PromptDiffChange(
                path="requiredVariables",
                type="changed",
                before=sorted(before_required - after_required),
                after=sorted(after_required - before_required),
            )
        )

    _diff_mapping("metadata", before.metadata, after.metadata, changes)
    _diff_mapping(
        "approval",
        before.approval.to_dict() if before.approval else {},
        after.approval.to_dict() if after.approval else {},
        changes,
    )
    return PromptDiff(
        from_id=before.id,
        from_version=before.version,
        to_id=after.id,
        to_version=after.version,
        changes=tuple(changes),
    )


def validate_semantic_version(version: str) -> bool:
    if _parse_semver(version) is None:
        raise ValueError(f"invalid semantic version: {version}")
    return True


def sign_prompt_manifest(
    manifest: dict[str, Any],
    secret: str | bytes,
    *,
    key_id: str = "local",
) -> dict[str, Any]:
    """Return a manifest copy with a deterministic HMAC-SHA256 signature."""

    signed = dict(manifest)
    signed["signature"] = {
        "algorithm": PROMPT_MANIFEST_SIGNATURE_ALGORITHM,
        "keyId": key_id,
        "value": _manifest_signature_value(manifest, secret),
    }
    return signed


def verify_prompt_manifest_signature(manifest: dict[str, Any], secret: str | bytes) -> bool:
    signature = manifest.get("signature")
    if not isinstance(signature, dict):
        return False
    if signature.get("algorithm") != PROMPT_MANIFEST_SIGNATURE_ALGORITHM:
        return False
    value = signature.get("value")
    if not isinstance(value, str):
        return False
    expected = _manifest_signature_value(manifest, secret)
    return hmac.compare_digest(value, expected)


def prompt_manifest_digest(manifest: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_prompt_manifest(manifest).encode("utf-8")).hexdigest()


def canonical_prompt_manifest(manifest: dict[str, Any]) -> str:
    return json.dumps(
        _without_signature(manifest),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )


def _manifest_signature_value(manifest: dict[str, Any], secret: str | bytes) -> str:
    raw_secret = secret.encode("utf-8") if isinstance(secret, str) else secret
    payload = canonical_prompt_manifest(manifest).encode("utf-8")
    return hmac.new(raw_secret, payload, hashlib.sha256).hexdigest()


def _without_signature(manifest: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in manifest.items() if key != "signature"}


def _diff_mapping(
    prefix: str,
    before: dict[str, Any],
    after: dict[str, Any],
    changes: list[PromptDiffChange],
) -> None:
    for key in sorted(set(before) | set(after)):
        if before.get(key) == after.get(key):
            continue
        content_key = key in _CONTENT_METADATA_KEYS
        changes.append(
            PromptDiffChange(
                path=f"{prefix}.{key}",
                type=_change_type(key in before, key in after),
                before_hash=_sha256_json(before.get(key))
                if content_key and key in before
                else None,
                after_hash=_sha256_json(after.get(key))
                if content_key and key in after
                else None,
                before=_sanitize_workflow_value(before.get(key))
                if not content_key and key in before
                else None,
                after=_sanitize_workflow_value(after.get(key))
                if not content_key and key in after
                else None,
            )
        )


def _change_type(has_before: bool, has_after: bool) -> str:
    if has_before and has_after:
        return "changed"
    if has_after:
        return "added"
    return "removed"


def _sha256_json(value: Any) -> str:
    return hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode(
            "utf-8"
        )
    ).hexdigest()


def _optional_str(value: Any) -> str | None:
    return None if value is None else str(value)


def _parse_semver(version: str) -> tuple[int, int, int, str | None] | None:
    match = _SEMVER.match(version)
    if match is None:
        return None
    return (
        int(match.group(1)),
        int(match.group(2)),
        int(match.group(3)),
        match.group(4),
    )


def _version_sort_key(version: str) -> tuple[int, int, int, int, str, str]:
    parsed = _parse_semver(version)
    if parsed is None:
        return (-1, -1, -1, -1, "", version)
    major, minor, patch, prerelease = parsed
    release_weight = 1 if prerelease is None else 0
    return (major, minor, patch, release_weight, prerelease or "", "")


def _matches_semver_selector(version: str, selector: str) -> bool:
    parsed = _parse_semver(version)
    if parsed is None:
        return False
    if selector in {"*", "latest"}:
        return True
    if selector.startswith("^"):
        base = _parse_semver(selector[1:])
        if base is None:
            return False
        lower = _compare_semver(parsed, base) >= 0
        if base[0] > 0:
            upper = parsed[0] == base[0]
        elif base[1] > 0:
            upper = parsed[0] == 0 and parsed[1] == base[1]
        else:
            upper = parsed[:3] == base[:3]
        return lower and upper
    if selector.startswith("~"):
        base = _parse_semver(selector[1:])
        return (
            base is not None
            and _compare_semver(parsed, base) >= 0
            and parsed[0] == base[0]
            and parsed[1] == base[1]
        )
    constraints = selector.split()
    if not constraints:
        return False
    return all(_matches_constraint(parsed, constraint) for constraint in constraints)


def _matches_constraint(
    version: tuple[int, int, int, str | None],
    constraint: str,
) -> bool:
    for operator in (">=", "<=", ">", "<", "="):
        if constraint.startswith(operator):
            base = _parse_semver(constraint[len(operator) :])
            if base is None:
                return False
            cmp = _compare_semver(version, base)
            return {
                ">=": cmp >= 0,
                "<=": cmp <= 0,
                ">": cmp > 0,
                "<": cmp < 0,
                "=": cmp == 0,
            }[operator]
    base = _parse_semver(constraint)
    return base is not None and _compare_semver(version, base) == 0


def _compare_semver(
    left: tuple[int, int, int, str | None],
    right: tuple[int, int, int, str | None],
) -> int:
    left_core = left[:3]
    right_core = right[:3]
    if left_core != right_core:
        return 1 if left_core > right_core else -1
    left_pre = left[3]
    right_pre = right[3]
    if left_pre == right_pre:
        return 0
    if left_pre is None:
        return 1
    if right_pre is None:
        return -1
    return 1 if left_pre > right_pre else -1
