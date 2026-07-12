# 21 - Eval CI Gate + Prompt Release Candidate

Shows a release-style prompt workflow using the v2.1.0 eval runner and the
existing Prompt Registry + Evals APIs:

- run `gavio eval run` against a YAML/JSON suite;
- compare candidate score against a stored baseline report;
- enforce `--fail-under` and `--max-regression` gates;
- write metadata-safe JSON and JUnit reports for CI;
- keep raw prompt outputs out of stored reports.

```bash
pip install -r requirements.txt
gavio eval run suite.yaml \
  --baseline baseline-report.json \
  --fail-under 1.0 \
  --max-regression 0.0 \
  --report eval-report.json \
  --junit eval-junit.xml \
  --pretty \
  --summary
```

The same scenario is also available as a small Python API example:

```bash
python eval_ci_gate.py
```

This example is intentionally offline. It uses fixed completion outputs so the
gate is deterministic and safe for CI.

See also: [Prompt Registry + Evals](../../../docs/prompt-registry-evals.md).
