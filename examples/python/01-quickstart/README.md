# 01 · Quickstart

The smallest possible Gavio program. Dev mode runs everything in-process (mock
provider + stdout audit) — **no API key, no network**.

```bash
pip install -r requirements.txt
python quickstart.py
```

You'll see the email + IBAN redacted before the (mock) provider, restored in the
reply, and an audit line showing `pii=EMAIL,IBAN`.

Next: [02 · production-gateway](../02-production-gateway/) ·
[Python guide](../../../docs/packages/python.md)
