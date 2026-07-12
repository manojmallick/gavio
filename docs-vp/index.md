---
layout: home
title: Gavio — AI request runtime and inspector for production systems
description: Inspect, govern, and route AI requests with PII protection, audit trails, reliability, cost intelligence, and policy packs as composable interceptors. Same API in Python, Java, and JavaScript.

hero:
  name: Gavio
  text: AI request runtime and inspector
  tagline: "PII protection · audit trails · reliability · Cost Intelligence · Policy Packs · Tool Runtime · Evals — as composable interceptors. Same API in Python, Java, and JavaScript."
  actions:
    - theme: brand
      text: Get Started →
      link: /guide/getting-started
    - theme: alt
      text: Architecture
      link: /guide/architecture
    - theme: alt
      text: GitHub
      link: https://github.com/manojmallick/gavio

features:
  - icon: 🛡️
    title: PII never leaves your process
    details: Email, IBAN, BSN, credit card, phone, IP, SSN, and secrets are detected (with checksums) and redacted before the provider call — then restored in the reply.
    link: /guide/interceptors
    linkText: Interceptors →
  - icon: 🔁
    title: Request runtime, not a black box
    details: Retry with backoff, fallback, timeouts, circuit breaker, and load balancing wrap every call. The Inspector shows live request traces, agent graphs, replay, RED stats, and a read-only production dashboard. v0.11.0 adds Cost Intelligence reports; v0.12.0 adds Policy Pack manifests; v0.13.0 adds richer runtime context and OpenRouter; v0.14.0 adds Tool Runtime checks; v1.0.0 adds the stable release gate and LTS policy; v1.1.0 adds metadata-safe runtime event export and integration recipes; v1.2.0 adds Cost Governance v2 budget policies and reports; v1.3.0 adds OpenTelemetry-style span export; v1.4.0 adds Prompt Registry + Evals; v1.5.0 adds Tool Runtime v2 governance; v1.6.0 adds the signed Policy Pack Catalog; v1.7.0 adds the optional self-hosted Control Plane.
    link: /guide/interceptors
    linkText: Production core →
  - icon: 🌐
    title: One API, three languages
    details: Python, Java, and JavaScript ship the same features at the same version — enforced by shared cross-SDK test vectors. Zero mandatory dependencies.
    link: /guide/python
    linkText: Pick your SDK →
  - icon: 🧪
    title: Zero-infra dev mode
    details: dev_mode runs the whole stack in-process with a mock provider and stdout audit. No API key, no network — write and test interceptor chains offline.
    link: /guide/getting-started
    linkText: Quickstart →
---

## Install

```bash
pip install gavio            # Python 3.10+
npm install gavio            # Node 18+
# Maven: io.github.manojmallick:gavio-core:1.7.0  (Java 17+)
```

## 30-second taste (Python, dev mode — no key)

```python
from gavio import Gateway
from gavio.interceptors.pii import PiiGuard

gw = Gateway.builder().dev_mode(True).use(PiiGuard()).build()

r = await gw.complete(messages=[
    {"role": "user", "content": "Email jan@example.com re NL91ABNA0417164300"}])

print(r.content)                 # PII restored in the reply
print(r.audit.pii_entity_types)  # ['EMAIL', 'IBAN']
```

The email and IBAN are detected and redacted before the provider ever sees them,
then restored in the response. [JavaScript and Java →](/guide/getting-started)
