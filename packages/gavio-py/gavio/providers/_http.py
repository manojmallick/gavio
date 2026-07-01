"""Tiny async JSON-over-HTTP helper built on stdlib (keeps core dependency-free)."""

from __future__ import annotations

import asyncio
import json
import urllib.error
import urllib.request

from ..exceptions import (
    ProviderUnavailableError,
    RateLimitError,
    ServerError,
)


async def post_json(
    url: str,
    payload: dict,
    headers: dict[str, str],
    timeout: float = 30.0,
) -> dict:
    """POST ``payload`` as JSON and return the parsed response.

    Runs the blocking request in a worker thread so callers stay async.
    Maps HTTP status families onto Gavio's transient error types so the
    retry/fallback policies can react.
    """

    def _do() -> dict:
        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        for key, value in headers.items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            if error.code == 429:
                raise RateLimitError(f"429 from provider: {body[:200]}") from error
            if error.code >= 500:
                raise ServerError(f"{error.code} from provider: {body[:200]}") from error
            raise ProviderUnavailableError(
                f"{error.code} from provider: {body[:200]}"
            ) from error
        except urllib.error.URLError as error:
            raise ProviderUnavailableError(f"network error: {error.reason}") from error

    return await asyncio.to_thread(_do)
