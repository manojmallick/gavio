# 25 - Platform Workflow Release

Builds a metadata-only platform workflow release artifact for prompts, evals,
policy packs, production trust evidence, and a platform runtime profile.

Run both commands from this directory, **in this order**:

```bash
pip install -r requirements.txt
python platform_workflow_release.py     # step 1: writes trust.json and profile.json
gavio workflow release workflow.json --output workflow-release.json --pretty   # step 2
```

`workflow.json` points at `trust.json` and `profile.json`. They are not checked in:
step 1 generates them. If you run step 2 first it stops with
`gavio workflow release: [Errno 2] No such file or directory: 'trust.json'`.
The command exits `0` when every gate passes and `1` otherwise (`--allow-failures` always exits `0`).

No API key, network, database, or running control plane is required.

