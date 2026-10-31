// Restarts the local test server, either cleanly (RCON "stop") or as a crash (kill -9 on its Java process), and
// waits until it is ready: "Done (" in server.out, RCON answers, mod data loaded. Used by the lifecycle tests
// (e2e/restart.mjs, e2e/restore.mjs, e2e/crash-fuzz.mjs). It only accepts a local host and only touches the
// Java process whose working directory is E2E_SERVER_DIR. CLI: node tools/server-restart.mjs [--kill]. Env:
// E2E_SERVER_DIR (required, must hold start.sh), E2E_HOST, E2E_RCON_PORT, E2E_RCON_PASSWORD (see e2e/lib.mjs),
// E2E_SERVER_JAR_PATTERN (pgrep pattern for the Java process). server.out is appended to, not overwritten, so
// earlier selftest lines survive.
import { execFileSync, spawn } from 'node:child_process'
import { closeSync, existsSync, openSync, readFileSync, readlinkSync, realpathSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Rcon } from '../e2e/node_modules/rcon-client/lib/index.js'

export const E2E_SERVER_DIR = process.env.E2E_SERVER_DIR ?? join(dirname(fileURLToPath(import.meta.url)), '..', 'run')
const HOST = process.env.E2E_HOST ?? '127.0.0.1'
const RCON_PORT = Number(process.env.E2E_RCON_PORT ?? 25632)
const RCON_PASSWORD = process.env.E2E_RCON_PASSWORD ?? 'smpeco'
const JAR_PATTERN = process.env.E2E_SERVER_JAR_PATTERN ?? 'fabric-(server|launcher)'
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost', '::1'])

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

function guard(dir) {
  if (!LOCAL_HOSTS.has(HOST)) throw new Error(`server-restart: local host only (E2E_HOST=${HOST})`)
  if (!existsSync(join(dir, 'start.sh'))) throw new Error(`server-restart: ${dir}/start.sh is missing, set E2E_SERVER_DIR to the server folder`)
}

function cwdOf(pid) {
  try {
    return readlinkSync(`/proc/${pid}/cwd`)
  } catch {}
  for (const lsof of ['lsof', '/usr/sbin/lsof', '/usr/bin/lsof']) {
    try {
      const out = execFileSync(lsof, ['-a', '-p', String(pid), '-d', 'cwd', '-Fn'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
      const line = out.split('\n').find((l) => l.startsWith('n'))
      return line ? line.slice(1) : null
    } catch {}
  }
  return null
}

export function serverPid(dir = E2E_SERVER_DIR) {
  let pids = []
  try {
    pids = execFileSync('pgrep', ['-f', JAR_PATTERN], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
      .split('\n').map((s) => Number(s.trim())).filter((n) => n > 0)
  } catch {
    return null // no match
  }
  const want = realpathSync(dir)
  for (const pid of pids) {
    const cwd = cwdOf(pid)
    let real = cwd
    try { real = cwd ? realpathSync(cwd) : null } catch {}
    if (real === want) return pid
  }
  return null
}

function alive(pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch (err) {
    return err.code === 'EPERM'
  }
}

async function waitExit(pid, timeoutMs) {
  const end = Date.now() + timeoutMs
  while (alive(pid)) {
    if (Date.now() > end) throw new Error(`server-restart: process ${pid} still running after ${timeoutMs} ms`)
    await sleep(250)
  }
}

async function rconOnce(cmd) {
  const r = await Rcon.connect({ host: HOST, port: RCON_PORT, password: RCON_PASSWORD, timeout: 10000 })
  try {
    return await r.send(cmd)
  } finally {
    try { await r.end() } catch {}
  }
}

function tail(file, from, lines = 40) {
  try {
    return readFileSync(file).subarray(from).toString('utf8').split('\n').slice(-lines).join('\n')
  } catch {
    return '(server.out not readable)'
  }
}

const defaultLog = (m) => console.log(`[restart] ${m}`)

/**
 * With {@code pid} the process dies before the first await, which crash tests need for exact timing; looking up
 * the PID (pgrep + lsof) can take a few hundred milliseconds. Returns the time until the process ended in ms.
 */
export async function killServer({ pid = null, dir = E2E_SERVER_DIR, log = defaultLog } = {}) {
  guard(dir)
  const target = pid ?? serverPid(dir)
  if (!target) throw new Error(`server-restart: no running server in ${dir}`)
  const t0 = Date.now()
  process.kill(target, 'SIGKILL')
  log(`kill -9 ${target} (simulating a crash)`)
  await waitExit(target, 30000)
  return Date.now() - t0
}

export async function stopServer({ kill = false, dir = E2E_SERVER_DIR, log = defaultLog } = {}) {
  guard(dir)
  const pid = serverPid(dir)
  if (!pid) throw new Error(`server-restart: no running server in ${dir}`)
  if (kill) return killServer({ pid, dir, log })
  const t0 = Date.now()
  log(`stop per RCON (PID ${pid})`)
  try {
    await rconOnce('stop')
  } catch (err) {
    log(`no RCON reply to "stop" (${err.message}), waiting for the process to exit anyway`)
  }
  await waitExit(pid, 180000)
  return Date.now() - t0
}

/**
 * Refuses to run if a server is already running, because two servers would share one world. With {@code
 * expectFailure} the start must abort: waits for {@code failurePattern} and the process exit, kills it after
 * {@code exitTimeoutMs} and reports {@code exited: false}. A server that becomes ready instead is an error.
 */
export async function startServer({
  dir = E2E_SERVER_DIR, log = defaultLog, expectFailure = false,
  failurePattern = /Server start aborted/, exitTimeoutMs = 120000,
} = {}) {
  guard(dir)
  const running = serverPid(dir)
  if (running) throw new Error(`server-restart: server still running (PID ${running}), stop it first`)
  const out = join(dir, 'server.out')
  const offset = existsSync(out) ? statSync(out).size : 0
  const fd = openSync(out, 'a')
  let child
  try {
    child = spawn('./start.sh', [], { cwd: dir, detached: true, stdio: ['ignore', fd, fd] })
    child.unref()
  } finally {
    closeSync(fd)
  }
  const fresh = () => {
    // offset is a byte position from statSync: slice as a Buffer, since characters like » take several bytes in
    // UTF-8
    try { return readFileSync(out).subarray(offset).toString('utf8') } catch { return '' }
  }
  log(`./start.sh started (PID ${child.pid})${expectFailure ? ', expecting refusal' : ', waiting for "Done ("'}`)
  const t1 = Date.now()
  if (expectFailure) {
    for (;;) {
      const text = fresh()
      if (failurePattern.test(text)) break
      if (Date.now() - t1 > 300000) throw new Error(`server-restart: neither refusal nor exit after 300 s\n${tail(out, offset)}`)
      if (Date.now() - t1 > 5000 && !serverPid(dir) && child.exitCode !== null) {
        throw new Error(`server-restart: process exited, but without ${failurePattern}\n${tail(out, offset)}`)
      }
      try {
        if (/balance=\d+/.test(await rconOnce('smpeco treasury'))) throw new Error('ready')
      } catch (err) {
        // the refusal may have been logged during this request, in which case the server is shutting down, not
        // ready
        if (err.message === 'ready' && !failurePattern.test(fresh())) throw new Error(`server-restart: server is ready despite the expected refusal\n${tail(out, offset)}`)
      }
      await sleep(500)
    }
    const deadline = Date.now() + exitTimeoutMs
    let pid = serverPid(dir)
    while (pid && Date.now() < deadline) {
      await sleep(500)
      pid = serverPid(dir)
    }
    const exited = !pid
    if (!exited) {
      log(`process ${pid} still running ${exitTimeoutMs / 1000} s after the refusal, kill -9`)
      process.kill(pid, 'SIGKILL')
      await waitExit(pid, 30000)
    }
    return { exited, log: fresh() }
  }
  for (;;) {
    const text = fresh()
    if (text.includes('Done (')) break
    if (child.exitCode !== null || (Date.now() - t1 > 5000 && !serverPid(dir))) {
      throw new Error(`server-restart: start failed\n${tail(out, offset)}`)
    }
    if (Date.now() - t1 > 300000) throw new Error(`server-restart: timeout during start\n${tail(out, offset)}`)
    await sleep(500)
  }
  // RCON comes up after "Done (", mod data loads on SERVER_STARTED; tests only make sense after that
  for (;;) {
    try {
      if (/balance=\d+/.test(await rconOnce('smpeco treasury'))) break
    } catch {}
    if (failurePattern.test(fresh())) throw new Error(`server-restart: start refused\n${tail(out, offset)}`)
    if (Date.now() - t1 > 360000) throw new Error('server-restart: RCON/mod not ready after start')
    await sleep(500)
  }
  return { pid: serverPid(dir), startMs: Date.now() - t1, log: fresh() }
}

export async function restartServer({ kill = false, dir = E2E_SERVER_DIR, log = defaultLog } = {}) {
  const stopMs = await stopServer({ kill, dir, log })
  const { pid, startMs } = await startServer({ dir, log })
  log(`ready (PID ${pid}, stop ${(stopMs / 1000).toFixed(1)} s, start ${(startMs / 1000).toFixed(1)} s)`)
  return { pid, stopMs, startMs }
}

function isMain() {
  try {
    return !!process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))
  } catch {
    return false
  }
}

if (isMain()) {
  restartServer({ kill: process.argv.includes('--kill') }).then(
    () => process.exit(0),
    (err) => { console.error(err.message); process.exit(1) },
  )
}
