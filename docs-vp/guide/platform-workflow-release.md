---
description: "Build metadata-only Gavio platform workflow release artifacts across prompts, evals, policy packs, trust bundles, and runtime profiles."
---

# Platform Workflow Release

Since: `3.0.0`

The Platform Workflow Release joins Gavio's prompt registry, eval runner, policy
pack catalog, production trust bundle, and platform runtime profile into one
metadata-only release artifact.

```bash
gavio workflow release workflow.json --output workflow-release.json --pretty
```

The command fails closed when eval, prompt, policy, trust, or runtime-profile
gates fail. Use `--allow-failures` only when you want a blocked review artifact
for triage.

## Manifest

```json
{
  "schemaVersion": "gavio.platform-workflow.v1",
  "workflowId": "support-platform-release",
  "release": { "version": "3.0.0", "tag": "v3.0.0", "commit": "abc1234" },
  "prompts": {
    "manifest": "prompts.json",
    "promptId": "support.reply",
    "promptVersion": "1.1.0",
    "fromVersion": "1.0.0"
  },
  "evals": [{ "suite": "suite.json", "failUnder": 1.0 }],
  "policies": [{ "id": "core-policy", "pathOrName": "core" }],
  "trustBundle": { "path": "trust.json" },
  "runtimeProfile": { "path": "profile.json" }
}
```

The output includes a stable `workflowHash`, prompt release bundle evidence,
eval gate results, policy pack signature evidence, trust bundle verification,
and runtime-profile readiness. Raw prompt text, raw model output, and
content-bearing metadata keys are omitted or replaced with hashes.

## Control Plane

The self-hosted control plane stores workflow releases at
`/api/workflow-releases` using the same file, SQLite, and Postgres record-store
path as other control-plane resources.

```bash
curl -X POST http://127.0.0.1:8787/api/workflow-releases \
  -H 'content-type: application/json' \
  -d @workflow-release.json
```

See
[`examples/python/25-platform-workflow-release`](https://github.com/manojmallick/gavio/tree/main/examples/python/25-platform-workflow-release)
for an offline end-to-end project.
