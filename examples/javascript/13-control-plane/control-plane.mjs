import { Gateway } from 'gavio'

const gateway = await Gateway.fromConfig({
  devMode: true,
  control_plane: {
    url: process.env.GAVIO_CONTROL_PLANE_URL ?? 'http://127.0.0.1:8787',
    runtime_key: process.env.GAVIO_RUNTIME_KEY ?? 'gav_rt_missing',
    policy_source: process.env.GAVIO_POLICY_SOURCE ?? 'project:prod-support',
    cache_path: '.gavio-control-plane-cache.json',
    fail_mode: 'open',
    timeout_ms: 200,
  },
})

const config = gateway.controlPlaneConfig ?? {}
console.log('source :', config.cache?.loadedFrom)
console.log('policy :', config.policySource)
console.log('project:', config.projectId || '(not loaded)')
