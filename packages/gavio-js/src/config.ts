/**
 * Config loader (F-DX-05) — build a Gateway from an object or a JSON file.
 *
 *   const gw = await Gateway.fromConfig('gateway.json')
 *
 * JSON is supported out of the box; string values expand ${ENV_VAR}.
 */

import { readFileSync } from 'node:fs'
import { loadControlPlaneConfig } from './control-plane.js'
import { ConfigurationError } from './errors.js'
import { Gateway } from './gateway.js'
import { auditInterceptor } from './interceptors/audit/index.js'
import { hashingEmbedder, redisCacheBackend, semanticCache } from './interceptors/cache/index.js'
import { costControl, modelPolicy, rateLimiter } from './interceptors/governance/index.js'
import { promptInjectionGuard } from './interceptors/injection.js'
import { piiGuard } from './interceptors/pii/index.js'
import { retryInterceptor, timeoutPolicy } from './interceptors/reliability/index.js'

type Cfg = Record<string, unknown>

export function loadConfig(path: string): Cfg {
  const text = readFileSync(path, 'utf8')
  if (!path.endsWith('.json')) {
    throw new ConfigurationError('JS config loader supports JSON only (use .json)')
  }
  return expand(JSON.parse(text)) as Cfg
}

function expand(obj: unknown): unknown {
  if (Array.isArray(obj)) return obj.map(expand)
  if (obj && typeof obj === 'object') {
    return Object.fromEntries(Object.entries(obj).map(([k, v]) => [k, expand(v)]))
  }
  if (typeof obj === 'string') {
    return obj.replace(/\$\{(\w+)\}/g, (_, v: string) => process.env[v] ?? '')
  }
  return obj
}

export async function buildFromConfig(config: Cfg): Promise<Gateway> {
  const gatewayOptions: Cfg = {}
  if (config['provider']) gatewayOptions['provider'] = config['provider']
  if (config['model']) gatewayOptions['model'] = config['model']
  if (config['devMode'] ?? config['dev_mode']) gatewayOptions['devMode'] = true
  if (config['dryRun'] ?? config['dry_run']) gatewayOptions['dryRun'] = true
  const controlPlane = (config['controlPlane'] ?? config['control_plane']) as Cfg | undefined
  if (controlPlane !== undefined) {
    const cachePath = controlPlane['cachePath'] ?? controlPlane['cache_path']
    gatewayOptions['controlPlaneConfig'] = await loadControlPlaneConfig({
      url: String(controlPlane['url']),
      runtimeKey: String(controlPlane['runtimeKey'] ?? controlPlane['runtime_key']),
      policySource: String(controlPlane['policySource'] ?? controlPlane['policy_source']),
      cachePath: typeof cachePath === 'string' ? cachePath : undefined,
      failMode: (controlPlane['failMode'] ?? controlPlane['fail_mode'] ?? 'open') as never,
      timeoutMs: Number(controlPlane['timeoutMs'] ?? controlPlane['timeout_ms'] ?? 2000),
    })
  }

  let gw = new Gateway(gatewayOptions)
  const ic = (config['interceptors'] as Record<string, Cfg> | undefined) ?? {}

  const cfg = (name: string): Cfg | null => {
    const entry = ic[name]
    return entry && entry['enabled'] !== false ? entry : null
  }

  let c: Cfg | null
  if ((c = cfg('audit'))) {
    gw = gw.use(
      auditInterceptor({
        sink: (c['sink'] as 'stdout') ?? 'stdout',
        hashChain: Boolean(c['hashChain'] ?? c['hash_chain']),
      }),
    )
  }
  if ((c = cfg('prompt_injection'))) {
    gw = gw.use(promptInjectionGuard({ action: (c['action'] as 'block' | 'flag') ?? 'block' }))
  }
  if ((c = cfg('pii_guard'))) {
    gw = gw.use(
      piiGuard({
        sensitivity: (c['sensitivity'] as never) ?? 'strict',
        mode: (c['mode'] as never) ?? 'redact',
      }),
    )
  }
  if ((c = cfg('cost_control'))) {
    gw = gw.use(
      costControl({
        hardCapUsd: Number(c['hardCapUsd'] ?? c['hard_cap_usd']),
        softCapUsd: (c['softCapUsd'] ?? c['soft_cap_usd']) as number | undefined,
        scope: (c['scope'] as never) ?? 'global',
        window: (c['window'] as never) ?? 'day',
      }),
    )
  }
  if ((c = cfg('rate_limiter'))) {
    gw = gw.use(
      rateLimiter({
        maxRequestsPerMinute: (c['maxRequestsPerMinute'] ?? c['max_requests_per_minute']) as
          | number
          | undefined,
        maxTokensPerMinute: (c['maxTokensPerMinute'] ?? c['max_tokens_per_minute']) as
          | number
          | undefined,
        scope: (c['scope'] as never) ?? 'global',
      }),
    )
  }
  if ((c = cfg('model_policy'))) {
    gw = gw.use(modelPolicy({ roles: (c['roles'] as Record<string, string[]>) ?? {} }))
  }
  if ((c = cfg('semantic_cache'))) {
    const embedder = (c['enableSemantic'] ?? c['enable_semantic']) ? hashingEmbedder() : undefined
    const backend =
      c['backend'] === 'redis'
        ? redisCacheBackend({ url: (c['redisUrl'] ?? c['redis_url']) as string | undefined })
        : undefined
    gw = gw.use(
      semanticCache({
        backend,
        embedder,
        similarityThreshold: Number(c['similarityThreshold'] ?? c['similarity_threshold'] ?? 0.95),
      }),
    )
  }
  if ((c = cfg('timeout'))) {
    gw = gw.use(
      timeoutPolicy({ timeoutSeconds: Number(c['timeoutSeconds'] ?? c['timeout_seconds'] ?? 30) }),
    )
  }
  if ((c = cfg('retry'))) {
    gw = gw.use(
      retryInterceptor({
        maxAttempts: Number(c['maxAttempts'] ?? c['max_attempts'] ?? 3),
        baseDelayMs: Number(c['baseDelayMs'] ?? c['base_delay_ms'] ?? 500),
      }),
    )
  }

  return gw
}
