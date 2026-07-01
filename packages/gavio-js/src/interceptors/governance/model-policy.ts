/** modelPolicy (F-GOV-04) — per-role model allowlists (RBAC). */

import type { InterceptorContext } from '../../context.js'
import { ModelNotAllowedError } from '../../errors.js'
import type { GavioRequest } from '../../request.js'
import type { Interceptor } from '../base.js'

export interface ModelPolicyOptions {
  roles: Record<string, string[]>
  defaultRole?: string
  roleKey?: string
}

export function modelPolicy(options: ModelPolicyOptions): Interceptor {
  const { roles, defaultRole = 'default', roleKey = 'role' } = options
  return {
    name: 'model_policy',
    before(request: GavioRequest, _ctx: InterceptorContext): GavioRequest {
      const role = String(request.metadata?.[roleKey] ?? defaultRole)
      const allowed = roles[role] ?? []
      if (allowed.includes('*') || allowed.includes(request.model)) return request
      throw new ModelNotAllowedError(role, request.model)
    },
  }
}
