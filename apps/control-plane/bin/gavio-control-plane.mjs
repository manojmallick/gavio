#!/usr/bin/env node
import { startControlPlane } from '../src/server.mjs'

const port = Number(process.env.GAVIO_CONTROL_PLANE_PORT ?? 8787)
const host = process.env.GAVIO_CONTROL_PLANE_HOST ?? '127.0.0.1'
const statePath = process.env.GAVIO_CONTROL_PLANE_STATE

const { server, url } = await startControlPlane({ host, port, statePath })

console.log(`Gavio Control Plane listening on ${url}`)

process.on('SIGINT', () => {
  server.close(() => process.exit(0))
})
