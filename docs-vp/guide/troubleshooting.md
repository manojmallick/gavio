---
description: "Fixes for common Gavio setup, Inspector and command-line problems."
---

# Troubleshooting

Problems that come up when setting up and running Gavio, with what the message means and how to fix it. The messages below were reproduced with the Python SDK; the JavaScript and Java SDKs use the same variable names and settings.

## Setup

### `ConfigurationError: No provider configured. Call .provider(...), .adapter(...), or .dev_mode(True).`

The builder has no provider. Choose one:

```python
Gateway.builder().dev_mode(True).build()                              # mock provider, no key
Gateway.builder().provider(Provider.OPENAI).model("gpt-4o-mini").build()
```

### `ConfigurationError: ANTHROPIC_API_KEY not set (pass api_key= or set the env var)`

The provider key is missing. Export the variable listed in the [configuration reference](configuration.md#provider-credentials) (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY` or `GOOGLE_API_KEY`, ...) in the process that runs your code, or pass it in code. In a container, a notebook or an IDE run configuration, check that the variable is set there too, not only in your shell.

### `AZURE_OPENAI_API_KEY and AZURE_OPENAI_ENDPOINT must be set`

Azure needs both variables, and usually `AZURE_OPENAI_DEPLOYMENT` as well. The error appears on the first call, not when the gateway is built.

### `gavio: command not found`

The `gavio` command comes with the **Python** package. Install it in the environment you are using (`pip install gavio`), or run it through that environment (`python -m pip show gavio`; with a virtual environment, activate it first). The npm and Maven packages have no command-line tool.

### Everything shows a cost of `0.0`

Dev mode uses the mock provider, which has no price. Costs appear once you use a real provider with a known model.

## Inspector

### The Inspector does not start

It is off by default. Use `.inspect(True)` on the builder or set `GAVIO_INSPECT=1`, then open `http://127.0.0.1:7411` (or the port in `GAVIO_INSPECT_PORT`). See the [configuration reference](configuration.md#inspector).

### `inspector mode 'full' captures raw prompt/response content. Outside dev mode you must opt in...`

Full capture stores prompts and responses, so Gavio only allows it in dev mode unless you opt in. Use `mode="redacted"` or `"metadata"` (the default outside dev mode), or pass `InspectorConfig(unsafe_content_capture_ack=True)` if you accept the risk.

### `inspector bind '0.0.0.0' is not loopback; set an auth_token...`

You cannot expose the Inspector beyond localhost without a token. In code set `InspectorConfig(bind=..., auth_token=...)`. For `gavio inspect` pass `--token`:

```bash
gavio inspect --store audit.jsonl --bind 0.0.0.0 --token "$INSPECTOR_TOKEN"
```

Without `--token`, the current CLI prints a Python traceback ending in this `ConfigurationError` instead of a one-line message. Requests to a token-protected Inspector need `Authorization: Bearer <token>` and get `401` otherwise.

### `gavio inspect: [Errno 2] No such file or directory`

The `--store` file does not exist. Write one by adding `AuditInterceptor(sink="jsonl://audit.jsonl")` to your gateway and making at least one call. See [the CLI reference](cli.md#making-the-input-files).

## Command-line tools

### `gavio cost report` puts everything under `unknown`

Grouping by `tenant`, `feature`, `user`, `endpoint`, `environment`, `workflow` or `tool` needs those values in the records. Files written by the `jsonl://` audit sink in the Python SDK did not carry them when tested. Group by `agent_id`, `model`, `provider` or `session_id` instead, or supply records with a `cost_dimensions` object. Details in [the CLI reference](cli.md#gavio-cost-report).

### `gavio cost report: Invalid isoformat string: '1h'`

`--since` takes a full ISO 8601 timestamp with a time zone, for example `--since 2026-10-01T00:00:00Z`. Relative values such as `1h` are not supported. A timestamp **without** a zone (`2026-10-01T00:00:00`) currently crashes with `TypeError: can't compare offset-naive and offset-aware datetimes`; add `Z` or `+00:00`.

### `gavio cost report: group_by must be one of [...]`

The message lists the valid keys: `agent_id`, `endpoint`, `environment`, `feature`, `middleware_chain`, `model`, `provider`, `session_id`, `tenant`, `tool`, `user`, `workflow`.

### `gavio workflow release: [Errno 2] No such file or directory: 'trust.json'`

The manifest points at files that must exist first. In the bundled example run `python platform_workflow_release.py` (it writes `trust.json` and `profile.json`) before `gavio workflow release workflow.json`.

### `gavio policy validate: unknown policy pack: <name>`

Use a catalog name from `gavio policy list` (`core`, `finance`, `healthcare`, `hr`, `legal`, `support`, `code-security`, `regional/eu`, `regional/india`, `regional/us`) or a path to an existing pack file.

### `gavio eval run` exits with 1 in CI

Exit code `1` means the gate failed (score below `--fail-under`, or a drop larger than `--max-regression` against `--baseline`) or an input file could not be read. The `gate.reasons` list in the JSON output, or the JUnit report, says which. Run with `--summary --pretty` to read it.

## Still stuck?

Open an issue at [github.com/manojmallick/gavio/issues](https://github.com/manojmallick/gavio/issues) with the SDK and version, the exact message, and a minimal script that reproduces it. Never paste API keys or raw prompts.
