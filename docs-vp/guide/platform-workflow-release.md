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
  "release": { "version": "3.1.0", "tag": "v3.1.0", "commit": "abc1234" },
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

## SDK support

| SDK | Library API | Notes |
|---|---|---|
| Python | `run_platform_workflow_release_file`, `build_platform_workflow_release`, `platform_workflow_release_hash` | Reference implementation; also the `gavio workflow release` CLI and YAML manifests |
| JavaScript | `runPlatformWorkflowReleaseFile`, `buildPlatformWorkflowRelease`, `platformWorkflowReleaseHash` from `gavio/workflow` | JSON documents only; asynchronous |
| Java | `PlatformWorkflow.runFile`, `PlatformWorkflow.build`, `PlatformWorkflow.releaseHash` in `gavio-core` | JSON documents only; Policy Pack evidence comes from `gavio-interceptor-pii` through `PlatformWorkflowPolicyResolver` |

CLIs stay Python-only. All three SDKs load the same vectors in
[`test-vectors/workflow/release.json`](https://github.com/manojmallick/gavio/blob/main/test-vectors/workflow/release.json), and the artifact shape is
[`spec/PlatformWorkflowRelease.schema.json`](https://github.com/manojmallick/gavio/blob/main/spec/PlatformWorkflowRelease.schema.json).

### `workflowHash`

`workflowHash` is `sha256:` over the **canonical JSON** of the artifact without
`workflowHash`, `evals[*].runner`, and `evals[*].source` (the interpreter
version and local suite path describe the machine, not the release). Canonical
JSON has no whitespace; sorts object keys by Unicode code point; emits non-ASCII
as UTF-8 and control characters as lowercase `\u00xx` escapes; writes integral
numbers as integers (`1.0` becomes `1`); and writes every other number as the
shortest round-trip decimal without an exponent. That is what makes the hash
byte-identical across languages.

> Python releases before this change hashed with `json.dumps`, which wrote
> `1.0` for integral floats. Artifacts hashed that way re-hash differently;
> rebuild them with the current SDK. Numbers inside the *input* prompt manifest
> feed its `digest`, so write integral values without a fraction (`1`, not
> `1.0`) if the same manifest must hash identically in every SDK.

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
