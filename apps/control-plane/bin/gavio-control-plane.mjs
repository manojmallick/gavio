#!/usr/bin/env node
import { startControlPlane } from '../src/server.mjs'

const port = Number(process.env.GAVIO_CONTROL_PLANE_PORT ?? 8787)
const host = process.env.GAVIO_CONTROL_PLANE_HOST ?? '127.0.0.1'
const storage = process.env.GAVIO_CONTROL_PLANE_STORAGE
const statePath = process.env.GAVIO_CONTROL_PLANE_STATE
const sqlitePath = process.env.GAVIO_CONTROL_PLANE_SQLITE_PATH
const databaseUrl = process.env.GAVIO_CONTROL_PLANE_DATABASE_URL

const { server, url, store } = await startControlPlane({ host, port, storage, statePath, sqlitePath, databaseUrl })

console.log(`Gavio Control Plane listening on ${url} (storage=${store.kind})`)

process.on('SIGINT', () => {
  server.close(() => process.exit(0))
})
