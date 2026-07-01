---
description: "How Gavio works: request lifecycle, the pre/post interceptor chain, executor policies for retry/timeout/fallback, and the canonical data model."
---

# Architecture

Gavio is a thin, deterministic pipeline around a provider call. Understanding
three things — the **chain**, the **context**, and the **data model** — is enough
to use or extend any part of it.

- [Request lifecycle](#request-lifecycle)
- [The interceptor chain (onion model)](#the-interceptor-chain-onion-model)
- [Executor policies (retry / timeout / fallback)](#executor-policies)
- [InterceptorContext](#interceptorcontext)
- [Data model](#data-model)
- [Provider adapters](#provider-adapters)

---

## Request lifecycle

```
  gw.complete(messages, agent_id, …)
        │
        ▼
  build GavioRequest  ── assign trace_id (UUID v7, time-sortable)
        │
        ▼
  ┌──────────────────────────── InterceptorChain ────────────────────────────┐
  │  before()  in registration order   →   PiiGuard, Audit, …               │
  │        │                                                                  │
  │        ▼                                                                  │
  │  ┌── executor policies (wrap the provider call) ──┐                       │
  │  │   Retry( Timeout( Fallback( provider ) ) )      │                      │
  │  └─────────────────────────────────────────────────┘                     │
  │        │                                                                  │
  │        ▼                                                                  │
  │  after()  in REVERSE order          →   …, PiiRestore, Audit             │
  └───────────────────────────────────────────────────────────────────────────┘
        │
        ▼
  GavioResponse  (content, cost, usage, interceptors_fired, audit)
```

The rule of thumb: **`before` runs left-to-right, `after` runs right-to-left**
(an onion). Register PII Guard before Audit so the audit record sees the
redacted prompt hash, never raw PII.

---

## The interceptor chain (onion model)

An interceptor implements up to three hooks (all optional except `name`):

| Hook | When | Purpose |
|---|---|---|
| `before(request, ctx)` | pre-call, in order | inspect/modify the request, or raise to abort |
| `after(response, ctx)` | post-call, reverse order | inspect/modify the response |
| `on_error(error, ctx)` | on any failure | observe; the error still propagates |

`dry_run_safe` (default `true`) controls whether the interceptor runs in
dry-run mode. If any `before` raises, the call aborts and every interceptor's
`on_error` fires.

---

## Executor policies

Retry, timeout, and fallback can't be plain `before`/`after` hooks — they need to
**re-invoke** (or race) the provider call. They implement `ExecutorPolicy`
(`around(request, ctx, call_next)`), and the Gateway composes them *around* the
provider executor, **first-registered = outermost**:

```
.use(RetryInterceptor)      ← outermost: retries the whole thing
.use(TimeoutPolicy)         ← each attempt gets its own timeout
.use(FallbackChain)         ← innermost: swaps provider on failure
```

They still appear in `interceptors_fired` and the audit trail.

---

## InterceptorContext

One `InterceptorContext` is created **per request** (never shared across
requests or threads). It's the scratch space interceptors use to pass signals to
the audit interceptor at the end of the chain:

- `interceptors_fired`, `pii_entity_types`, `pii_entity_counts`
- `cache_hit`, `cache_type`, `risk_score`, `guardrail_outcome`
- `state` — arbitrary inter-interceptor state (e.g. the PII replacement map used
  to restore values in the response)

The `Gateway` instance itself is safe to share across threads/tasks.

---

## Data model

Canonical shapes live in [`spec/`](https://github.com/manojmallick/gavio/tree/main/spec/) as JSON Schema. The wire format is
**camelCase** (Java/JS native); Python exposes the same fields in snake_case.

**GavioRequest** — `trace_id`, `messages`, `model`, `provider`, `agent_id`,
`parent_trace_id` (multi-agent DAG), `session_id`, `options`, `metadata`.

**GavioResponse** — `trace_id`, `content`, `model_version`, `usage`
(prompt/completion/total tokens), `cost_usd`, `latency_ms`, `cache_hit`,
`cache_type`, `interceptors_fired`, `audit`.

**AuditRecord** — metadata **and content hashes only**, never raw text:
`prompt_hash` / `response_hash` (SHA-256), token usage, cost, latency,
`pii_entity_types` / `pii_entity_counts`, `interceptors_fired`, `schema_version`.
`previous_hash` is reserved for the v0.2.0 hash-chain (`F-OBS-02`).

---

## Provider adapters

A `ProviderAdapter` implements `complete(request) → response`, optional
`stream(...)`, and `health_check()`. v0.1.0 ships **OpenAI**, **Anthropic**
(both over stdlib HTTP — no vendor SDK), and **Mock**. Cost is computed from
token usage via a shared pricing table. Adding a provider is a single class; see
[interceptors.md](./interceptors.md) and each package guide.
