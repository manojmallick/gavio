# 22 - Platform Feature Tour

An offline, end-to-end project that exercises the current v2.x platform
surface in one place. It is meant as the "show me how the pieces fit together"
example for teams evaluating Gavio beyond a quickstart.

It covers:

- privacy/security: PII, secret scanning, prompt-injection flagging, risk score;
- policy packs: core + FinTech + custom ticket-id policy pack;
- reliability/cache: retry, timeout, guardrails, semantic/exact cache;
- cost governance: model rerouting, budget policy, cost report rollups;
- observability: audit hash chain, multi-agent parent trace, metrics, runtime
  events, OTel-style spans;
- prompt management/evals: versioned template, deterministic eval report;
- tool runtime: schema/freshness/permission/approval/MCP metadata;
- ecosystem: integration metadata for a promptfoo-style CI workflow;
- enterprise/admin: control-plane config fallback metadata;
- trust/platform: Production Trust Package and Platform Runtime Profile.

```bash
pip install -r requirements.txt
python feature_tour.py
```

No API key, Redis, database, or running control-plane server is required.
