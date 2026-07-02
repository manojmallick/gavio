"""Config loader (F-DX-05) — build a Gateway from a dict or a JSON/YAML file.

    gw = Gateway.from_config("gateway.yaml")

Supports JSON out of the box (stdlib); YAML if PyYAML is installed. String
values expand ``${ENV_VAR}`` references.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from .exceptions import ConfigurationError


def load_config(path: str | Path) -> dict:
    text = Path(path).read_text(encoding="utf-8")
    if str(path).endswith((".yaml", ".yml")):
        try:
            import yaml  # optional dependency
        except ImportError as exc:  # pragma: no cover
            raise ConfigurationError(
                "YAML config requires PyYAML (`pip install pyyaml`) — or use JSON"
            ) from exc
        data = yaml.safe_load(text)
    else:
        data = json.loads(text)
    if not isinstance(data, dict):
        raise ConfigurationError("config root must be a mapping")
    return _expand(data)


def _expand(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {k: _expand(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_expand(v) for v in obj]
    if isinstance(obj, str):
        return os.path.expandvars(obj)
    return obj


def build_from_config(config: dict) -> Any:
    """Construct a :class:`~gavio.gateway.Gateway` from a config mapping."""
    from .gateway import Gateway
    from .interceptors.audit import AuditInterceptor
    from .interceptors.cache import HashingEmbedder, SemanticCache
    from .interceptors.governance import CostControl, ModelPolicy, RateLimiter
    from .interceptors.injection import PromptInjectionGuard
    from .interceptors.pii import PiiGuard
    from .interceptors.reliability import RetryInterceptor, TimeoutPolicy

    builder = Gateway.builder()
    if config.get("dev_mode"):
        builder.dev_mode(True)
    if config.get("dry_run"):
        builder.dry_run(True)
    if config.get("provider"):
        builder.provider(config["provider"])
    if config.get("model"):
        builder.model(config["model"])

    ic = config.get("interceptors", {})

    def cfg(name: str) -> dict | None:
        entry = ic.get(name)
        if isinstance(entry, dict) and entry.get("enabled", True):
            return entry
        return None

    if (c := cfg("audit")) is not None:
        builder.use(
            AuditInterceptor(
                sink=c.get("sink", "stdout"), hash_chain=bool(c.get("hash_chain", False))
            )
        )
    if (c := cfg("prompt_injection")) is not None:
        builder.use(PromptInjectionGuard(action=c.get("action", "block")))
    if (c := cfg("pii_guard")) is not None:
        builder.use(
            PiiGuard(
                sensitivity=c.get("sensitivity", "strict"),
                mode=c.get("mode", "redact"),
            )
        )
    if (c := cfg("cost_control")) is not None:
        builder.use(
            CostControl(
                hard_cap_usd=float(c["hard_cap_usd"]),
                soft_cap_usd=c.get("soft_cap_usd"),
                scope=c.get("scope", "global"),
                window=c.get("window", "day"),
            )
        )
    if (c := cfg("rate_limiter")) is not None:
        builder.use(
            RateLimiter(
                max_requests_per_minute=c.get("max_requests_per_minute"),
                max_tokens_per_minute=c.get("max_tokens_per_minute"),
                scope=c.get("scope", "global"),
            )
        )
    if (c := cfg("model_policy")) is not None:
        builder.use(ModelPolicy(roles=c.get("roles", {})))
    if (c := cfg("semantic_cache")) is not None:
        embedder = HashingEmbedder() if c.get("enable_semantic") else None
        backend = None
        if c.get("backend") == "redis":
            from .interceptors.cache.backends import RedisBackend

            backend = RedisBackend(url=c.get("redis_url", "redis://localhost:6379"))
        builder.use(
            SemanticCache(
                backend=backend,
                embedder=embedder,
                similarity_threshold=float(c.get("similarity_threshold", 0.95)),
                exact_ttl_seconds=int(c.get("exact_ttl_seconds", 3600)),
            )
        )
    if (c := cfg("timeout")) is not None:
        builder.use(TimeoutPolicy(timeout_seconds=float(c.get("timeout_seconds", 30))))
    if (c := cfg("retry")) is not None:
        builder.use(
            RetryInterceptor(
                max_attempts=int(c.get("max_attempts", 3)),
                base_delay_ms=int(c.get("base_delay_ms", 500)),
            )
        )

    return builder.build()
