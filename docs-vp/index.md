---
layout: home
title: Gavio — the open standard AI gateway for production systems
description: PII protection, audit trails, reliability, and cost control as composable interceptors. Same API in Python, Java, and JavaScript. Zero mandatory dependencies.

hero:
  name: Gavio
  text: The open standard AI gateway
  tagline: "PII protection · audit trails · reliability · cost control — as composable interceptors. Same API in Python, Java, and JavaScript."
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
    title: Reliable by default
    details: Retry with backoff, fallback, timeouts, circuit breaker, and load balancing wrap every call. Semantic caching, budgets/rate-limits, and guardrails ship in v0.2.0. Tamper-evident hash-chain audit — never raw text. v0.3.0 adds Prometheus metrics, prompt lineage, and composite risk scoring. v0.4.0 adds a distributed Redis cache backend. v0.5.0 adds cost-optimiser routing. v0.6.0 adds the embedded Inspector — a live dev-time visualizer for every request. v0.7.0 completes it with agent call graphs, replay, RED stats, and a read-only production dashboard. v0.11.0 adds Cost Intelligence reports; v0.12.0 adds Policy Pack manifests for core, FinTech, and custom regex-rule packs.
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
# Maven: io.github.manojmallick:gavio-core:0.12.0  (Java 17+)
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
