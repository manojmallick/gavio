# Self-hosted Control Plane

```bash
npm install
node control-plane.mjs
```

The example runs in fail-open mode. With no server running it prints an
`unavailable` config. Start `apps/control-plane` and provide `GAVIO_RUNTIME_KEY`
to load a real `project:prod-support` rollout.
