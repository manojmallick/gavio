import { createServer, type Server } from 'node:http'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { ControlPlaneClient, ControlPlaneError, Gateway } from '../../src/index.js'

const CONFIG = {
  schemaVersion: '1.0',
  configVersion: 'cfg_test',
  projectId: 'proj_support',
  environment: 'prod',
  policySource: 'project:prod-support',
  policy: { id: 'pol_support', name: 'Support', policyPack: 'support', rules: [] },
  budgets: [{ id: 'budget_support', scopeType: 'project', limitUsd: 25 }],
  rollout: { id: 'rollout_support', policyId: 'pol_support', status: 'active' },
  cache: { ttlSeconds: 120, failMode: 'open' },
} as const

let cleanup: (() => Promise<void>) | null = null

afterEach(async () => {
  if (cleanup !== null) await cleanup()
  cleanup = null
})

describe('ControlPlaneClient', () => {
  it('fetches runtime config and falls back to cache', async () => {
    const served = await serveConfig(CONFIG)
    cleanup = served.close
    const cachePath = join(mkdtempSync(join(tmpdir(), 'gavio-control-plane-')), 'cache.json')
    const client = new ControlPlaneClient({
      url: served.url,
      runtimeKey: 'gav_rt_test',
      policySource: 'project:prod-support',
      cachePath,
    })

    const first = await client.loadConfig()
    expect(first.cache.loadedFrom).toBe('control_plane')
    expect(first.policy.policyPack).toBe('support')

    served.failWith(503)

    const cached = await client.loadConfig()
    expect(cached.cache.loadedFrom).toBe('cache')
    expect(cached.policySource).toBe('project:prod-support')
  })

  it('fails closed when no cache is available', async () => {
    const served = await serveConfig(CONFIG)
    cleanup = served.close
    served.failWith(503)
    const client = new ControlPlaneClient({
      url: served.url,
      runtimeKey: 'gav_rt_test',
      policySource: 'project:prod-support',
      failMode: 'closed',
    })
    await expect(client.loadConfig()).rejects.toBeInstanceOf(ControlPlaneError)
  })

  it('loads config through Gateway.fromConfig', async () => {
    const served = await serveConfig(CONFIG)
    cleanup = served.close
    const gw = await Gateway.fromConfig({
      devMode: true,
      control_plane: {
        url: served.url,
        runtime_key: 'gav_rt_test',
        policy_source: 'project:prod-support',
        cache_path: join(mkdtempSync(join(tmpdir(), 'gavio-control-plane-')), 'gateway.json'),
      },
    })
    expect(gw.controlPlaneConfig?.projectId).toBe('proj_support')
  })
})

async function serveConfig(
  config: unknown,
): Promise<{ url: string; close: () => Promise<void>; failWith: (status: number) => void }> {
  let failureStatus = 0
  const server: Server = createServer((req, res) => {
    expect(req.headers.authorization).toBe('Bearer gav_rt_test')
    expect(req.url).toContain('/api/runtime/config?')
    if (failureStatus !== 0) {
      res.writeHead(failureStatus, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ error: 'unavailable' }))
      return
    }
    const body = JSON.stringify(config)
    res.writeHead(200, { 'content-type': 'application/json', 'content-length': body.length })
    res.end(body)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (address === null || typeof address === 'string') throw new Error('bad address')
  return {
    url: `http://127.0.0.1:${address.port}`,
    close: () => new Promise((resolve) => server.close(() => resolve())),
    failWith: (status: number) => {
      failureStatus = status
    },
  }
}
