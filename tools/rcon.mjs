// Usage: node tools/rcon.mjs "<command>" ["<command>" ...]. Env: E2E_HOST (default 127.0.0.1), E2E_RCON_PORT
// (default 25632), E2E_RCON_PASSWORD (default smpeco).
import { Rcon } from '../e2e/node_modules/rcon-client/lib/index.js'
const r = await Rcon.connect({
  host: process.env.E2E_HOST ?? '127.0.0.1',
  port: Number(process.env.E2E_RCON_PORT ?? 25632),
  password: process.env.E2E_RCON_PASSWORD ?? 'smpeco',
  timeout: 600000,
})
for (const c of process.argv.slice(2)) console.log(await r.send(c))
await r.end()
