/** Self-hosted control-plane runtime config client. */

import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { homedir } from 'node:os'

export type ControlPlaneFailMode = 'open' | 'closed'

export interface ControlPlaneRuntimeConfig {
  schemaVersion: '1.0'
  configVersion: string
  projectId: string
  environment: string
  policySource: string
  policy: Record<string, unknown>
  budgets: Array<Record<string, unknown>>
  rollout: Record<string, unknown>
  cache: { ttlSeconds: number; failMode: ControlPlaneFailMode; loadedFrom?: string }
  [key: string]: unknown
}

export interface ControlPlaneOptions {
  url: string
  runtimeKey: string
  policySource: string
  cachePath?: string
  failMode?: ControlPlaneFailMode
  timeoutMs?: number
}

export class ControlPlaneError extends Error {
  override name = 'ControlPlaneError'
}

export class ControlPlaneClient {
  readonly url: string
  readonly runtimeKey: string
  readonly policySource: string
  readonly cachePath: string
  readonly failMode: ControlPlaneFailMode
  readonly timeoutMs: number

  constructor(options: ControlPlaneOptions) {
    this.url = options.url.replace(/\/+$/, '')
    this.runtimeKey = options.runtimeKey
    this.policySource = options.policySource
    this.failMode = options.failMode ?? 'open'
    this.timeoutMs = options.timeoutMs ?? 2000
    this.cachePath = options.cachePath ?? defaultCachePath(this.url, this.policySource)
  }

  async loadConfig(): Promise<ControlPlaneRuntimeConfig> {
    try {
      const config = await this.fetchConfig()
      config.cache = { ...config.cache, loadedFrom: 'control_plane' }
      this.writeCache(config)
      return config
    } catch (error) {
      const cached = this.readCache()
      if (cached !== null) {
        cached.cache = { ...cached.cache, loadedFrom: 'cache' }
        return cached
      }
      if (this.failMode === 'closed') {
        throw new ControlPlaneError(
          `failed to load control-plane config for ${this.policySource}: ${String(error)}`,
        )
      }
      return unavailableConfig(this.policySource, this.failMode)
    }
  }

  private async fetchConfig(): Promise<ControlPlaneRuntimeConfig> {
    const params = new URLSearchParams({
      policy_source: this.policySource,
      fail_mode: this.failMode,
    })
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), this.timeoutMs)
    try {
      const response = await fetch(`${this.url}/api/runtime/config?${params}`, {
        headers: { authorization: `Bearer ${this.runtimeKey}` },
        signal: controller.signal,
      })
      if (!response.ok) throw new ControlPlaneError(`HTTP ${response.status}`)
      return (await response.json()) as ControlPlaneRuntimeConfig
    } finally {
      clearTimeout(timer)
    }
  }

  private readCache(): ControlPlaneRuntimeConfig | null {
    try {
      return JSON.parse(readFileSync(this.cachePath, 'utf8')) as ControlPlaneRuntimeConfig
    } catch {
      return null
    }
  }

  private writeCache(config: ControlPlaneRuntimeConfig): void {
    mkdirSync(dirname(this.cachePath), { recursive: true })
    writeFileSync(this.cachePath, `${JSON.stringify(config, null, 2)}\n`, 'utf8')
  }
}

export function loadControlPlaneConfig(
  options: ControlPlaneOptions,
): Promise<ControlPlaneRuntimeConfig> {
  return new ControlPlaneClient(options).loadConfig()
}

export function unavailableConfig(
  policySource: string,
  failMode: ControlPlaneFailMode = 'open',
): ControlPlaneRuntimeConfig {
  return {
    schemaVersion: '1.0',
    configVersion: 'unavailable',
    projectId: '',
    environment: '',
    policySource,
    policy: { id: 'unavailable', name: 'unavailable', rules: [] },
    budgets: [],
    rollout: { id: 'unavailable', policyId: 'unavailable', status: 'paused' },
    cache: { ttlSeconds: 0, failMode, loadedFrom: 'unavailable' },
  }
}

function defaultCachePath(url: string, policySource: string): string {
  const digest = createHash('sha256').update(`${url}|${policySource}`).digest('hex').slice(0, 16)
  return join(process.env['GAVIO_CACHE_DIR'] ?? join(homedir(), '.cache', 'gavio'), 'control-plane', `${digest}.json`)
}
