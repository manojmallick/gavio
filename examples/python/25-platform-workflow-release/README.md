# 25 - Platform Workflow Release

Builds a metadata-only platform workflow release artifact for prompts, evals,
policy packs, production trust evidence, and a platform runtime profile.

```bash
python platform_workflow_release.py
gavio workflow release workflow.json --output workflow-release.json --pretty
```

No API key, network, database, or running control plane is required.

