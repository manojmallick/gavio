/**
 * Cross-SDK canonical JSON, shared by the workflow release hash (F-DX-13) and the
 * tool-call arguments hash (F-ADP-03). A leaf module so low-level code such as
 * `GavioRequest` can use it without importing the workflow machinery.
 */

type Mapping = Record<string, unknown>

/**
 * Cross-SDK canonical JSON: compact separators, object keys sorted by Unicode
 * code point, non-ASCII emitted as-is, control characters as lowercase
 * `\u00xx` escapes, integral numbers written as integers (`1.0` -> `1`), and
 * every other number as the shortest round-trip decimal without an exponent.
 * `undefined` object values are omitted, as `JSON.stringify` does.
 */
export function canonicalJson(value: unknown): string {
  if (value === null || value === undefined) return 'null'
  if (typeof value === 'boolean') return value ? 'true' : 'false'
  if (typeof value === 'string') return JSON.stringify(value)
  if (typeof value === 'number') return canonicalNumber(value)
  if (typeof value === 'bigint') return value.toString()
  if (Array.isArray(value)) return `[${value.map((item) => canonicalJson(item)).join(',')}]`
  if (typeof value === 'object') {
    const entries = Object.entries(value as Mapping)
      .filter(([, item]) => item !== undefined)
      .sort(([left], [right]) => compareCodePoints(left, right))
    return `{${entries.map(([key, item]) => `${JSON.stringify(key)}:${canonicalJson(item)}`).join(',')}}`
  }
  throw new TypeError(`cannot canonicalize ${typeof value}`)
}

function canonicalNumber(value: number): string {
  if (!Number.isFinite(value)) throw new RangeError('canonical JSON cannot represent NaN or Infinity')
  if (Number.isInteger(value)) return BigInt(value).toString()
  const text = String(value)
  if (!/e/i.test(text)) return text
  // Shortest digits come back in exponent form below 1e-6; expand them.
  const [mantissa, exponent] = text.toLowerCase().split('e') as [string, string]
  const negative = mantissa.startsWith('-')
  const [whole, fraction = ''] = (negative ? mantissa.slice(1) : mantissa).split('.') as [string, string?]
  const digits = whole + fraction
  const point = whole.length + Number(exponent)
  let plain: string
  if (point <= 0) plain = `0.${'0'.repeat(-point)}${digits}`
  else if (point >= digits.length) plain = digits + '0'.repeat(point - digits.length)
  else plain = `${digits.slice(0, point)}.${digits.slice(point)}`
  return negative ? `-${plain}` : plain
}

function compareCodePoints(left: string, right: string): number {
  const a = Array.from(left)
  const b = Array.from(right)
  const length = Math.min(a.length, b.length)
  for (let index = 0; index < length; index += 1) {
    const difference = a[index]!.codePointAt(0)! - b[index]!.codePointAt(0)!
    if (difference !== 0) return difference
  }
  return a.length - b.length
}
