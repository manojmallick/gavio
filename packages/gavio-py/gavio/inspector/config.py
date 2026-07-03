"""InspectorConfig — settings for the Gavio Inspector (F-DX-09 / F-DX-10).

The inspector is OFF by default. It is enabled explicitly via
``Gateway.builder().inspect(...)`` or the ``GAVIO_INSPECT=1`` environment
variable — never implicitly by dev mode.
"""

from __future__ import annotations

from dataclasses import dataclass

from ..exceptions import ConfigurationError

MODES = ("full", "redacted", "metadata")

_LOOPBACK_BINDS = ("127.0.0.1", "localhost", "::1")


@dataclass
class InspectorConfig:
    """Configuration for the inspector bus, ring buffer, and HTTP server.

    ``mode`` controls content capture:

    * ``full`` — messages, response content, and mutation diffs are captured
      (secrets are still masked). Requires dev mode or an explicit
      ``unsafe_content_capture_ack``.
    * ``redacted`` — same event stream, but diffs omit the pre-mutation
      ("from") text.
    * ``metadata`` — no content at all; the content-bearing fields are
      structurally absent from every event.

    When ``mode`` is None it resolves at build time to ``full`` in dev mode
    and ``metadata`` otherwise.
    """

    enabled: bool = False
    mode: str | None = None
    port: int = 7411  # 0 = ephemeral
    bind: str = "127.0.0.1"
    auth_token: str | None = None
    max_traces: int = 1000
    unsafe_content_capture_ack: bool = False
    start_server: bool = True

    def resolve_mode(self, dev_mode: bool) -> str:
        """Return the effective capture mode for this gateway."""
        return self.mode if self.mode is not None else ("full" if dev_mode else "metadata")

    def validate(self, dev_mode: bool) -> None:
        """Raise :class:`ConfigurationError` on unsafe or invalid settings."""
        mode = self.resolve_mode(dev_mode)
        if mode not in MODES:
            raise ConfigurationError(f"inspector mode must be one of {MODES}, got {mode!r}")
        if mode == "full" and not dev_mode and not self.unsafe_content_capture_ack:
            raise ConfigurationError(
                "inspector mode 'full' captures raw prompt/response content. "
                "Outside dev mode you must opt in explicitly with "
                "InspectorConfig(unsafe_content_capture_ack=True) — or use "
                "mode='redacted' or 'metadata'."
            )
        if self.bind not in _LOOPBACK_BINDS and not self.auth_token:
            raise ConfigurationError(
                f"inspector bind {self.bind!r} is not loopback; set an "
                "auth_token before exposing the inspector beyond localhost"
            )
