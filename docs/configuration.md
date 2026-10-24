# Configuration reference

Gavio is configured in code (the gateway builder) and, for a few settings, with environment variables. The same variable names are read by the Python, JavaScript and Java SDKs.

## Provider credentials

Set the variable that matches your provider and Gavio reads it when the gateway is built. You can also pass the key in code (`api_key=` in Python), which wins over the variable. A missing key raises a `ConfigurationError` that names the variable (for example `ANTHROPIC_API_KEY not set (pass api_key= or set the env var)`).

| Provider | Variables | Default endpoint |
|---|---|---|
| OpenAI | `OPENAI_API_KEY` | `https://api.openai.com/v1` |
| Anthropic | `ANTHROPIC_API_KEY` | `https://api.anthropic.com/v1` |
| Gemini | `GEMINI_API_KEY`, or `GOOGLE_API_KEY` as a fallback | `https://generativelanguage.googleapis.com/v1beta` |
| Azure OpenAI | `AZURE_OPENAI_API_KEY`, `AZURE_OPENAI_ENDPOINT` (both required), `AZURE_OPENAI_DEPLOYMENT` | your endpoint |
| OpenRouter | `OPENROUTER_API_KEY`, optional `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` |
| Ollama (local) | none. Optional `OLLAMA_HOST` | `http://localhost:11434` |
| Mock (dev mode) | none | in process |

Dev mode (`.dev_mode(True)` / `devMode: true`) uses the mock provider and needs no key or network. Never commit keys; keep them in your environment or a secret manager.

## Inspector

The [Inspector](inspector.md) is **off by default**, also in dev mode. Turn it on in code with `.inspect(True)` or with environment variables, no code change needed:

| Variable | Meaning | Default |
|---|---|---|
| `GAVIO_INSPECT` | `1` or `true` enables the Inspector | off |
| `GAVIO_INSPECT_PORT` | Port of the local UI (`0` picks a free port) | `7411` |
| `GAVIO_INSPECT_MODE` | `full`, `redacted` or `metadata` | `full` in dev mode, `metadata` otherwise |

Capture modes:

| Mode | What is captured |
|---|---|
| `full` | Messages, responses and redaction diffs (secrets are still masked). Outside dev mode it needs an explicit opt-in: `InspectorConfig(unsafe_content_capture_ack=True)` |
| `redacted` | The same events, but diffs leave out the original text |
| `metadata` | No content at all; those fields are absent from every event |

Settings you can only change in code (`InspectorConfig`): `bind` (default `127.0.0.1`), `auth_token`, `max_traces` (default `1000`, the size of the in-memory ring buffer) and `start_server`. Binding to anything other than `127.0.0.1`, `localhost` or `::1` requires an `auth_token`, otherwise building the gateway raises `ConfigurationError`.

## Control Plane cache

| Variable | Meaning | Default |
|---|---|---|
| `GAVIO_CACHE_DIR` | Where SDKs keep the cached control-plane runtime config used as a fallback when the control plane is unreachable | `~/.cache/gavio` |

See [Control Plane](control-plane.md).

## Audit and runtime-event output

These are set on the builder, not by environment variable:

| Setting | Example | Result |
|---|---|---|
| Audit sink | `AuditInterceptor(sink="stdout://")` | One JSON record per call on stdout |
| | `AuditInterceptor(sink="jsonl://audit.jsonl")` | One JSON record per call appended to a file |
| Runtime exporter | `.exporter(JsonlRuntimeExporter(stream=f))` | One metadata-only event per line |

Audit records contain metadata and SHA-256 hashes of the prompt and response, never the text. The JSONL files feed [`gavio inspect`, `gavio cost report` and `gavio events convert`](cli.md).

## Test-only variables

`GAVIO_TEST_REDIS_URL` is used by the cache tests when a Redis server is available. You do not need it to use Gavio.
