"""ModelPolicy (F-GOV-04) — per-role model allowlists (RBAC)."""

from __future__ import annotations

from ...context import InterceptorContext
from ...exceptions import ModelNotAllowedError
from ...request import GavioRequest
from ..base import Interceptor


class ModelPolicy(Interceptor):
    """Allow a model only if the caller's role permits it.

    The caller's role is read from ``request.metadata['role']`` (falling back to
    ``default_role``). An allowlist of ``["*"]`` permits any model.
    """

    def __init__(
        self,
        roles: dict[str, list[str]],
        default_role: str = "default",
        role_key: str = "role",
    ) -> None:
        self.roles = roles
        self.default_role = default_role
        self.role_key = role_key

    @property
    def name(self) -> str:
        return "model_policy"

    async def before(
        self, request: GavioRequest, ctx: InterceptorContext
    ) -> GavioRequest:
        role = str(request.metadata.get(self.role_key, self.default_role))
        allowed = self.roles.get(role, [])
        if "*" in allowed or request.model in allowed:
            return request
        raise ModelNotAllowedError(role, request.model)
