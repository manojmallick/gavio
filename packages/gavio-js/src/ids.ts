/**
 * UUID v7 generation — time-sortable, unique identifiers for traces.
 *
 * UUID v7 layout (RFC 9562): 48-bit Unix millisecond timestamp, 4-bit version,
 * 12 bits used here as a per-millisecond monotonic sequence (rand_a), 2-bit
 * variant, 62 bits of randomness. Ported directly from the Python `_ids.py`.
 */

import { randomBytes } from 'node:crypto'

let lastMs = -1
let seq = 0 // 12-bit per-millisecond sequence in rand_a, for monotonicity

/**
 * Return a [unix_ms, sequence] pair that is monotonically non-decreasing.
 *
 * Within a single millisecond the 12-bit sequence increments so IDs stay
 * strictly ordered (RFC 9562 method 1). If the sequence overflows, the
 * timestamp is nudged forward. JavaScript is single-threaded per event loop,
 * so no lock is required.
 */
function nextTimestampAndSeq(): [number, number] {
  const nowMs = Date.now()
  if (nowMs > lastMs) {
    lastMs = nowMs
    seq = randomBytes(2).readUInt16BE(0) & 0x0fff
  } else {
    seq += 1
    if (seq > 0x0fff) {
      lastMs += 1
      seq = 0
    }
  }
  return [lastMs, seq]
}

function toHex(value: bigint, width: number): string {
  return value.toString(16).padStart(width, '0')
}

/** Return a new UUID version 7 (time-ordered, monotonic within a process). */
export function uuid7(): string {
  const [unixMs, randA] = nextTimestampAndSeq()

  // 48-bit millisecond timestamp occupies bits 80..127. Use BigInt for the
  // shift — JS bitwise ops are 32-bit and would truncate Date.now() (~1.75e12).
  const ms = BigInt(unixMs) & 0xffffffffffffn

  const randBuf = randomBytes(8)
  const randB = randBuf.readBigUInt64BE(0) & 0x3fffffffffffffffn // 62 bits

  const value =
    (ms << 80n) |
    (0x7n << 76n) | // version 7
    (BigInt(randA) << 64n) |
    (0b10n << 62n) | // variant
    randB

  const hex = toHex(value, 32)
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(
    16,
    20,
  )}-${hex.slice(20)}`
}

/** Return a fresh trace id as a string. */
export function newTraceId(): string {
  return uuid7()
}
