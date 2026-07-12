# Self-hosted Control Plane

```bash
mvn -q compile exec:java
```

The example runs in fail-open mode. With no server running it prints an
`unavailable` config. Start `apps/control-plane` and provide `GAVIO_RUNTIME_KEY`
to load a real `project:prod-support` rollout.
