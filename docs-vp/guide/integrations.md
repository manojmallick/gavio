# Integrations

Since: `1.1.0`

Use Gavio as the embedded runtime governance layer beside the tools teams
already use.

```text
Application
  -> Gavio embedded runtime
  -> gateway / provider / framework
  -> observability, eval, and security tools through runtime events
```

| Tool | Let that tool own | Let Gavio own |
|---|---|---|
| LiteLLM | Multi-provider proxy, virtual keys, routing, rate/budget tiers | App-level PII, audit hashes, policy packs, tool checks, feature labels |
| Portkey | AI gateway, org governance, provider routing, gateway logs | Embedded policy decisions, metadata-only audit, interceptor facts |
| Helicone | Gateway observability, request analytics, prompt workflows | Local runtime controls before and after calls |
| Langfuse | Traces, prompts, eval datasets, review loops | Metadata-safe runtime event source and policy/audit context |
| OpenLIT | OpenTelemetry-native observability | Runtime event source and privacy-preserving labels |
| promptfoo | Evals, red-team tests, CI checks | Runtime assertions, policy outcomes, cost/PII regression signals |

Recommended metadata:

```json
{
  "tenant": "acme",
  "feature": "support-chat",
  "environment": "prod",
  "workflow": "ticket-triage"
}
```

These scalar labels are copied into runtime events and cost dimensions without
exporting raw prompt or response text.
