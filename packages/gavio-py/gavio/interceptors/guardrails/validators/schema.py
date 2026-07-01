"""JsonSchemaValidator (F-QUA-01) — validate structured output against a schema.

A pragmatic, zero-dependency subset of JSON Schema: ``type`` (object, array,
string, number, integer, boolean, null), ``required``, ``properties``,
``items``, and ``enum``. Enough to enforce the shape of structured LLM output.
"""

from __future__ import annotations

import json
from typing import Any

from ..validator import OutputValidator, ValidationResult

_TYPE_CHECKS = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


def _validate(instance: Any, schema: dict, path: str = "$") -> str | None:
    expected = schema.get("type")
    if expected is not None:
        check = _TYPE_CHECKS.get(expected)
        if check is not None and not check(instance):
            return f"{path}: expected type {expected}"

    if "enum" in schema and instance not in schema["enum"]:
        return f"{path}: value not in enum {schema['enum']}"

    if expected == "object" and isinstance(instance, dict):
        for key in schema.get("required", []):
            if key not in instance:
                return f"{path}: missing required property '{key}'"
        for key, subschema in schema.get("properties", {}).items():
            if key in instance:
                err = _validate(instance[key], subschema, f"{path}.{key}")
                if err:
                    return err

    if expected == "array" and isinstance(instance, list) and "items" in schema:
        for i, item in enumerate(instance):
            err = _validate(item, schema["items"], f"{path}[{i}]")
            if err:
                return err

    return None


class JsonSchemaValidator(OutputValidator):
    def __init__(self, schema: dict) -> None:
        self.schema = schema

    @property
    def name(self) -> str:
        return "json_schema"

    def validate(self, content: str) -> ValidationResult:
        try:
            instance = json.loads(content)
        except (json.JSONDecodeError, ValueError):
            return ValidationResult.failed("output is not valid JSON")
        err = _validate(instance, self.schema)
        if err:
            return ValidationResult.failed(err)
        return ValidationResult.passed()
