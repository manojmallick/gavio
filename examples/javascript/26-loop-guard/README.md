# 26 - Loop Guard (JavaScript)

Stops a runaway agent. A planner agent that never decides it is done keeps calling the
model; the loop guard counts the provider calls of one **root run** and refuses the next
one once a limit is reached. **No API key, no network**: the mock provider answers, with a
demo price so the cost cap has something to add up.

```bash
npm install
node runaway-agent.mjs
```

The example runs the same loop twice and prints why each run was stopped:

```text
== Step cap: at most 25 calls per run
provider calls : 25
stop reason    : max_steps (max_steps_reached, action stop)
run so far     : 25 steps, $3.00
same run       : yes

== Cost cap: $1.00 per run (the 25-step cap is still set)
provider calls : 9
stop reason    : max_cost (max_cost_reached, action stop)
run so far     : 9 steps, $1.08
same run       : yes
```

What to notice:

- **One run.** The first call is the root; every later call passes `parentTraceId: root.traceId`, so the guard counts them together. A loop must pass `parentTraceId` or `metadata.runId`: calls with neither are separate runs that no limit can link.
- **Where the limit bites.** A limit is what the run may consume, checked *before* a call:
  `25` steps allow 25 calls and the 26th never reaches the provider. Cost is only known after
  a call, so the call that crosses the $1.00 cap (the 9th, $1.08) still happens and the 10th
  is refused.
- **The stop is an error you can catch.** `LoopGuardError.decision` has the verdict (which limit, steps, cost, elapsed time) and never contains prompts or tool arguments.
- **The policy.** `loopGuard({ policy: { id, maxSteps: 25, maxCostUsd: 1.0 } })`. The other limits are `max_wall_time` and
  `repeated_tool_call`; the other actions are `fallback` (reroute to a cheaper model) and
  `dry_run` (record the breach, change nothing).

The loop guard ships in Gavio `3.2.1` and later, which is what this example's manifest pins.

See also: [Loop guard](../../../docs/interceptors.md#loop-guard-f-rel-09)
