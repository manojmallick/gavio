/**
 * Minimal RESP2 (Redis Serialization Protocol) client over `node:net`.
 *
 * Hand-rolled, zero runtime dependencies — matches the project convention of
 * stdlib-only providers/interceptors. Supports exactly the commands the cache
 * backends need (GET, SET, DEL, SADD, SREM, SMEMBERS); not a general client.
 */

import { connect, type Socket } from 'node:net'

export type RespValue = string | number | null | RespValue[] | RespError

/** A `-ERR ...` reply from the server. Rejects the corresponding command's promise. */
export class RespError extends Error {}

interface RedisUrlParts {
  host: string
  port: number
}

export function parseRedisUrl(url: string): RedisUrlParts {
  const parsed = new URL(url)
  return { host: parsed.hostname || 'localhost', port: Number(parsed.port) || 6379 }
}

function encodeCommand(args: (string | number)[]): string {
  let out = `*${args.length}\r\n`
  for (const arg of args) {
    const s = String(arg)
    out += `$${Buffer.byteLength(s, 'utf8')}\r\n${s}\r\n`
  }
  return out
}

/** A single persistent RESP connection. Commands are serialized (no pipelining). */
export class RespClient {
  private socket: Socket | null = null
  private connecting: Promise<Socket> | null = null
  private buffer: Buffer = Buffer.alloc(0)
  private pending: Array<{
    resolve: (v: RespValue) => void
    reject: (e: Error) => void
  }> = []
  private queue: Promise<unknown> = Promise.resolve()

  constructor(
    private readonly host: string,
    private readonly port: number,
  ) {}

  private async ensureConnected(): Promise<Socket> {
    if (this.socket) return this.socket
    if (this.connecting) return this.connecting
    this.connecting = new Promise<Socket>((resolve, reject) => {
      const socket = connect({ host: this.host, port: this.port })
      socket.once('connect', () => {
        this.socket = socket
        resolve(socket)
      })
      socket.once('error', (err) => {
        this.socket = null
        this.connecting = null
        reject(err)
      })
      socket.on('data', (chunk: Buffer) => {
        this.buffer = Buffer.concat([this.buffer, chunk])
        this.drain()
      })
      socket.on('close', () => {
        this.socket = null
        this.connecting = null
      })
    })
    return this.connecting
  }

  private drain(): void {
    while (this.pending.length > 0) {
      let result: [RespValue, number] | undefined
      try {
        result = this.tryParse(0)
      } catch (err) {
        // Malformed/unrecoverable framing — the buffer position is no longer
        // trustworthy, so drop everything buffered and fail all waiters.
        this.buffer = Buffer.alloc(0)
        const failure = err instanceof Error ? err : new Error(String(err))
        const waiters = this.pending.splice(0)
        for (const waiter of waiters) waiter.reject(failure)
        return
      }
      if (result === undefined) return
      const [value, rest] = result
      this.buffer = this.buffer.subarray(rest)
      const waiter = this.pending.shift()!
      if (value instanceof RespError) waiter.reject(value)
      else waiter.resolve(value)
    }
  }

  /** Try to parse one RESP value starting at `offset`. Returns undefined if more data is needed. */
  private tryParse(offset: number): [RespValue, number] | undefined {
    if (offset >= this.buffer.length) return undefined
    const type = String.fromCharCode(this.buffer[offset]!)
    const lineEnd = this.buffer.indexOf('\r\n', offset)
    if (lineEnd === -1) return undefined
    const line = this.buffer.toString('utf8', offset + 1, lineEnd)
    const afterLine = lineEnd + 2

    switch (type) {
      case '+':
        return [line, afterLine]
      case '-':
        return [new RespError(line), afterLine]
      case ':':
        return [Number(line), afterLine]
      case '$': {
        const len = Number(line)
        if (len < 0) return [null, afterLine]
        if (this.buffer.length < afterLine + len + 2) return undefined
        const data = this.buffer.toString('utf8', afterLine, afterLine + len)
        return [data, afterLine + len + 2]
      }
      case '*': {
        const count = Number(line)
        if (count < 0) return [null, afterLine]
        const items: RespValue[] = []
        let pos = afterLine
        for (let i = 0; i < count; i++) {
          const item = this.tryParse(pos)
          if (item === undefined) return undefined
          items.push(item[0])
          pos = item[1]
        }
        return [items, pos]
      }
      default:
        throw new Error(`unexpected RESP type byte: ${type}`)
    }
  }

  async command(...args: (string | number)[]): Promise<RespValue> {
    const run = async (): Promise<RespValue> => {
      const socket = await this.ensureConnected()
      return new Promise<RespValue>((resolve, reject) => {
        this.pending.push({ resolve, reject })
        socket.write(encodeCommand(args), (err) => {
          if (err) {
            this.pending.pop()
            reject(err)
          }
        })
      })
    }
    const result = this.queue.then(run, run)
    this.queue = result.catch(() => undefined)
    return result
  }

  close(): void {
    this.socket?.end()
    this.socket = null
    this.connecting = null
  }
}
