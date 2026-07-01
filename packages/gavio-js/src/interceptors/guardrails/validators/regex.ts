/** Regex allow/deny validators (F-QUA-02). */

import { failed, passed, type OutputValidator, type ValidationResult } from '../validator.js'

/** Fails if the content matches ANY denied pattern. */
export function regexDenylist(patterns: (string | RegExp)[]): OutputValidator {
  const compiled = patterns.map((p) => (typeof p === 'string' ? new RegExp(p) : p))
  return {
    name: 'regex_denylist',
    validate(content: string): ValidationResult {
      for (const re of compiled) {
        if (re.test(content)) return failed(`content matched denied pattern /${re.source}/`)
      }
      return passed()
    },
  }
}

/** Fails unless the content matches at least ONE allowed pattern. */
export function regexAllowlist(patterns: (string | RegExp)[]): OutputValidator {
  const compiled = patterns.map((p) => (typeof p === 'string' ? new RegExp(p) : p))
  return {
    name: 'regex_allowlist',
    validate(content: string): ValidationResult {
      if (compiled.some((re) => re.test(content))) return passed()
      return failed('content matched no allowed pattern')
    },
  }
}
