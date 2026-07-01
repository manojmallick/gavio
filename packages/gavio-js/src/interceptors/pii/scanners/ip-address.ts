/** IP address scanner — IPv4 and IPv6, validated via node:net isIP. */

import { isIP } from 'node:net'
import type { ScanContext } from '../context.js'
import { makeMatch } from '../match.js'
import type { PiiMatch } from '../match.js'
import type { PiiScanner } from '../scanner.js'

const IPV4 = String.raw`(?:\d{1,3}\.){3}\d{1,3}`
// Permissive IPv6 candidate — allows empty groups for "::" compression. False
// positives are filtered by isIP validation below.
const IPV6 = String.raw`(?:[A-Fa-f0-9]{0,4}:){2,7}[A-Fa-f0-9]{0,4}`
const IP = new RegExp(String.raw`(?<![\w.])(?:${IPV6}|${IPV4})(?![\w.])`, 'g')

function validIp(candidate: string): boolean {
  return isIP(candidate) !== 0
}

export function ipAddressScanner(): PiiScanner {
  return {
    entityType: 'IP_ADDRESS',
    tier: 1,
    scan(text: string, ctx: ScanContext): PiiMatch[] {
      const out: PiiMatch[] = []
      for (const m of text.matchAll(IP)) {
        if (!validIp(m[0])) continue
        const idx = ctx.nextIndex('IP_ADDRESS')
        out.push(
          makeMatch({
            entityType: 'IP_ADDRESS',
            start: m.index,
            end: m.index + m[0].length,
            value: m[0],
            replacement: `[IP_ADDRESS_${idx}]`,
          }),
        )
      }
      return out
    },
  }
}
