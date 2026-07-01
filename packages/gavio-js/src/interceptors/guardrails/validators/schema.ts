/** jsonSchemaValidator (F-QUA-01) — zero-dependency JSON Schema subset. */

import { failed, passed, type OutputValidator, type ValidationResult } from '../validator.js'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any

const TYPE_CHECKS: Record<string, (v: Json) => boolean> = {
  object: (v) => typeof v === 'object' && v !== null && !Array.isArray(v),
  array: (v) => Array.isArray(v),
  string: (v) => typeof v === 'string',
  number: (v) => typeof v === 'number',
  integer: (v) => typeof v === 'number' && Number.isInteger(v),
  boolean: (v) => typeof v === 'boolean',
  null: (v) => v === null,
}

function validate(instance: Json, schema: Json, path = '$'): string | null {
  const expected = schema.type as string | undefined
  if (expected !== undefined) {
    const check = TYPE_CHECKS[expected]
    if (check && !check(instance)) return `${path}: expected type ${expected}`
  }
  if ('enum' in schema && !(schema.enum as Json[]).some((e) => e === instance)) {
    return `${path}: value not in enum`
  }
  if (expected === 'object' && typeof instance === 'object' && instance !== null) {
    for (const key of (schema.required as string[] | undefined) ?? []) {
      if (!(key in instance)) return `${path}: missing required property '${key}'`
    }
    const props = (schema.properties as Record<string, Json> | undefined) ?? {}
    for (const [key, sub] of Object.entries(props)) {
      if (key in instance) {
        const err = validate(instance[key], sub, `${path}.${key}`)
        if (err) return err
      }
    }
  }
  if (expected === 'array' && Array.isArray(instance) && 'items' in schema) {
    for (let i = 0; i < instance.length; i++) {
      const err = validate(instance[i], schema.items, `${path}[${i}]`)
      if (err) return err
    }
  }
  return null
}

export function jsonSchemaValidator(schema: Json): OutputValidator {
  return {
    name: 'json_schema',
    validate(content: string): ValidationResult {
      let instance: Json
      try {
        instance = JSON.parse(content)
      } catch {
        return failed('output is not valid JSON')
      }
      const err = validate(instance, schema)
      return err ? failed(err) : passed()
    },
  }
}
