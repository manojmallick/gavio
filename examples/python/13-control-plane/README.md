# Self-hosted Control Plane

```bash
pip install -r requirements.txt
python control_plane.py
```

The example runs in fail-open mode. With no server running it prints an
`unavailable` config.

For the v3.1.0 Control Plane UX flow, start the local app with demo seeding:

```bash
cd ../../../apps/control-plane
GAVIO_CONTROL_PLANE_DEMO=1 npm start
```

Open `http://127.0.0.1:8787`, use `Seed demo`, then run this example with the
returned values:

```bash
export GAVIO_RUNTIME_KEY=gav_rt_...
export GAVIO_POLICY_SOURCE=project:demo-support-...
python control_plane.py
```

The same records can be created manually from the UI: project, environment,
policy, budget, runtime key, and rollout.
