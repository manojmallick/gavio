# Gavio examples

Runnable example projects. More languages coming; Python first.

## Python — [`python/`](./python/)

| # | Project | What it shows | Needs a key? |
|---|---|---|---|
| 01 | [quickstart](./python/01-quickstart/) | PII redact + restore, audit, cost — in dev mode | no |
| 02 | [production-gateway](./python/02-production-gateway/) | Full stack: audit → PII guard → timeout → retry, real provider (mock fallback) | optional |
| 03 | [custom-scanner](./python/03-custom-scanner/) | Write a domain-specific PII scanner + test it with `GavioTestKit` | no |

Each project is self-contained:

```bash
cd examples/python/01-quickstart
pip install -r requirements.txt      # installs gavio from PyPI
python quickstart.py
```

Examples 01 and 03 need no API key (dev mode). Example 02 uses a real provider if
`ANTHROPIC_API_KEY` or `OPENAI_API_KEY` is set, and falls back to the mock
provider otherwise so it always runs.
