"""Prompt Registry + Evals foundation.

The registry renders versioned chat templates into Gavio messages and attaches
existing PromptLineage metadata. Eval reports are metadata-safe by default:
they store output hashes and assertion details, not raw model output.
"""

from __future__ import annotations

import asyncio
import hashlib
import inspect
import re
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from ..request import GavioRequest
from ..types import Message, PromptLineage, Provider

_PLACEHOLDER = re.compile(r"{{\s*([A-Za-z_][A-Za-z0-9_.-]*)\s*}}")


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

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> PromptTemplate:
        return cls(
            id=str(data["id"]),
            version=str(data["version"]),
            messages=[dict(message) for message in data["messages"]],
            required_variables=tuple(
                str(v)
                for v in data.get("requiredVariables", data.get("required_variables", ()))
            ),
            metadata=dict(data.get("metadata") or {}),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "version": self.version,
            "messages": [dict(message) for message in self.messages],
            "requiredVariables": list(self.required_variables),
            "metadata": dict(self.metadata),
        }

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


class PromptRegistry:
    """In-memory registry for versioned prompt templates."""

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
        self._latest[parsed.id] = parsed.version
        return parsed

    def get(self, template_id: str, version: str | None = None) -> PromptTemplate:
        resolved = version or self._latest.get(template_id)
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

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> EvalCase:
        return cls(
            id=str(data["id"]),
            template_id=str(data["templateId"]),
            template_version=data.get("templateVersion"),
            variables=dict(data.get("variables") or {}),
            assertions=tuple(EvalAssertion.from_dict(item) for item in data.get("assertions", ())),
            metadata=dict(data.get("metadata") or {}),
        )


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

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "templateId": self.template_id,
            "templateVersion": self.template_version,
            "passed": self.passed,
            "score": self.score,
            "outputHash": self.output_hash,
            "assertions": [result.to_dict() for result in self.assertions],
            "lineage": _lineage_to_camel(self.lineage),
        }


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
                )
            )
        return EvalReport(self.id, tuple(results))

    def run_sync(self, registry: PromptRegistry, complete: CompletionFn) -> EvalReport:
        return asyncio.run(self.run(registry, complete))


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
