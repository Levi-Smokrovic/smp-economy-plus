// Runs all e2e/<feature>.mjs one after another and sums up the results. Usage: node run-all.mjs [feature ...]
// [--no-restart]. Order: foundation first, then the features, then the tests that really restart the server
// (skip them with --no-restart or E2E_NO_RESTART=1). modules: four restarts. crash-fuzz: without a name it runs
// as a rotation of three item or money paths with kill -9 plus one save-all path, and each successful run moves
// on to the next paths (counter in E2E_SERVER_DIR/.e2e-fuzz-rotation; after a failure the same paths run again).
// E2E_FUZZ_AT=<n> forces rotation n without changing the counter. "node run-all.mjs crash-fuzz" runs all paths,
// about 18 restarts. restore: start refusal and RESTORE_ALLOWED on real starts, four restarts; without a name
// only when the last successful run is missing or 7 days old, E2E_RESTORE=1 forces it. restart: clean stop and
// kill -9 with everything open at once; always last.
import { spawn } from 'node:child_process'
import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { Rcon } from 'rcon-client'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'

const dir = dirname(fileURLToPath(import.meta.url))
const skip = new Set(['lib.mjs', 'run-all.mjs'])
const RESTARTING = ['modules.mjs', 'crash-fuzz.mjs', 'restore.mjs', 'restart.mjs'] // these restart the server, and run last in this order
const FUZZ_PATHS = 3 // paths per rotation, plus one save-all path
const FUZZ_STATE = join(E2E_SERVER_DIR, '.e2e-fuzz-rotation')
const RESTORE_STATE = join(E2E_SERVER_DIR, '.e2e-restore-ok')
const RESTORE_EVERY_MS = 7 * 24 * 3600 * 1000
const args = process.argv.slice(2)
const noRestart = args.includes('--no-restart') || process.env.E2E_NO_RESTART === '1'
const wanted = args.filter((a) => !a.startsWith('--')).map((a) => a.replace(/\.mjs$/, ''))
const fullRun = wanted.length === 0

function readState(file) {
  try { return readFileSync(file, 'utf8').trim() } catch { return null }
}

function writeState(file, value) {
  try { writeFileSync(file, `${value}\n`) } catch (err) { console.log(`[E2E] WARN ${file} not written: ${err.message}`) }
}

const fileEnv = {}
let fuzzAt = null // null = complete run or not included
let fuzzAdvance = false
if (noRestart) {
  for (const f of RESTARTING) skip.add(f)
} else {
  if (!wanted.includes('crash-fuzz')) {
    if (fullRun) {
      const forced = process.env.E2E_FUZZ_AT
      const raw = forced ?? readState(FUZZ_STATE) ?? '0'
      fuzzAt = /^\d+$/.test(raw) ? Number(raw) : 0
      fuzzAdvance = forced === undefined
      fileEnv['crash-fuzz.mjs'] = { FUZZ_ROTATE: String(FUZZ_PATHS), FUZZ_ROTATE_AT: String(fuzzAt) }
      console.log(`[E2E] INFO crash-fuzz: rotation ${fuzzAt} (${FUZZ_PATHS} paths + 1 save-all; all paths: node run-all.mjs crash-fuzz)`)
    } else {
      skip.add('crash-fuzz.mjs')
    }
  }
  if (!wanted.includes('restore')) {
    const last = readState(RESTORE_STATE)
    const lastMs = last ? Date.parse(last) : NaN
    const due = process.env.E2E_RESTORE === '1' || !Number.isFinite(lastMs) || Date.now() - lastMs >= RESTORE_EVERY_MS
    if (fullRun && due) {
      console.log(`[E2E] INFO restore.mjs due (last successful run: ${last ?? 'none'})`)
    } else {
      skip.add('restore.mjs')
      if (fullRun) console.log(`[E2E] INFO restore.mjs not due (last successful run ${last}; E2E_RESTORE=1 forces it)`)
    }
  }
}
// Tests of extra modules only run when those modules are on; for a full run enable all extras in modules.json.
const NEEDS = {
  'afk.mjs': ['afk'], 'bounty.mjs': ['bounty'], 'clans.mjs': ['clans'], 'crates.mjs': ['crates'], 'daily.mjs': ['daily'],
  'events.mjs': ['events'], 'market.mjs': ['market'], 'minigames.mjs': ['minigames'], 'news.mjs': ['news'],
  'restart.mjs': ['casino', 'crates', 'minigames', 'news'],
}
let running = null
try {
  const r = await Rcon.connect({ host: process.env.E2E_HOST ?? '127.0.0.1', port: Number(process.env.E2E_RCON_PORT ?? 25632),
    password: process.env.E2E_RCON_PASSWORD ?? 'smpeco', timeout: 30000 })
  const first = (await r.send('smpeco module list')).replace(/§[0-9a-fk-or]/gi, '')
  // the tests call /smpeco selftest, which does not exist without the JVM flag
  const selftest = (await r.send('smpeco selftest -')).replace(/§[0-9a-fk-or]/gi, '')
  if (/unknown or incomplete|incorrect argument/i.test(selftest)) {
    console.log('[E2E] WARN /smpeco selftest is missing: start the test server with -Dsmpeconomy.selftest=true (see e2e/README.md)')
  }
  await r.end()
  const m = first.match(/running=([a-z,]*)/)
  if (m) running = new Set(m[1].split(',').filter(Boolean))
} catch (err) {
  console.log(`[E2E] WARN smpeco module list failed: ${err.message}, running all tests`)
}
const needsOf = (f) => NEEDS[f] ?? (f.startsWith('casino') ? ['casino'] : [])
const skippedForModules = []
if (running) {
  for (const f of readdirSync(dir)) {
    const missing = needsOf(f).filter((id) => !running.has(id))
    if (f.endsWith('.mjs') && missing.length > 0 && !skip.has(f)) {
      skip.add(f)
      skippedForModules.push(`${f} (${missing.join(', ')} off)`)
    }
  }
  if (skippedForModules.length > 0) console.log(`[E2E] INFO skipped, module off: ${skippedForModules.join(', ')}`)
}
const rank = (f) => (f === 'foundation.mjs' ? 0 : RESTARTING.includes(f) ? 2 + RESTARTING.indexOf(f) : 1)
const files = readdirSync(dir)
  .filter((f) => f.endsWith('.mjs') && !skip.has(f) && !f.startsWith('_'))
  .filter((f) => fullRun || wanted.includes(f.replace(/\.mjs$/, '')))
  .sort((a, b) => rank(a) - rank(b) || a.localeCompare(b))

function runFile(file) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [join(dir, file)], { cwd: dir, env: { ...process.env, ...(fileEnv[file] ?? {}) } })
    let out = ''
    const onData = (chunk) => { out += chunk; process.stdout.write(`  [${file}] ${chunk}`.replace(/\n(?=.)/g, `\n  [${file}] `)) }
    child.stdout.on('data', onData)
    child.stderr.on('data', onData)
    child.on('close', (code) => {
      const m = out.match(/\[E2E\] SUMMARY passed=(\d+) failed=(\d+)/)
      resolve({ file, code, passed: m ? Number(m[1]) : 0, failed: m ? Number(m[2]) : 1, summary: !!m })
    })
  })
}

let starterWasOn = false
// Pause automatic breaking news (persisted in news.json): a market shock coming due would disturb the price
// asserts of other features. news.mjs restores its own initial state.
try {
  const r = await Rcon.connect({ host: process.env.E2E_HOST ?? '127.0.0.1', port: Number(process.env.E2E_RCON_PORT ?? 25632),
    password: process.env.E2E_RCON_PASSWORD ?? 'smpeco', timeout: 30000 })
  console.log('[E2E] ' + (await r.send('news auto off')).replace(/§[0-9a-fk-or]/gi, ''))
  // New bots would get the starter kit on first join and hold unexpected items. starter.mjs enables it for
  // itself and restores starter.json afterwards.
  const show = (await r.send('smpeco starterkit show')).replace(/§[0-9a-fk-or]/gi, '')
  starterWasOn = /giveKit=true/.test(show)
  if (starterWasOn) console.log('[E2E] ' + (await r.send('smpeco starterkit disable')).replace(/§[0-9a-fk-or]/gi, ''))
  await r.end()
} catch (err) {
  console.log(`[E2E] WARN news auto off failed: ${err.message}`)
}

const results = []
try {
for (const file of files) {
  console.log(`\n=== ${file}${file === 'crash-fuzz.mjs' && fuzzAt !== null ? ` (Rotation ${fuzzAt})` : ''} ===`)
  const r = await runFile(file)
  results.push(r)
  const ok = r.code === 0 && r.failed === 0 && r.summary
  // advance state only on success: after a failure the next run repeats the same paths
  if (ok && file === 'crash-fuzz.mjs' && fuzzAt !== null && fuzzAdvance) writeState(FUZZ_STATE, fuzzAt + 1)
  if (ok && file === 'restore.mjs') writeState(RESTORE_STATE, new Date().toISOString())
}
} finally {
  if (starterWasOn) {
    try {
      const r = await Rcon.connect({ host: process.env.E2E_HOST ?? '127.0.0.1', port: Number(process.env.E2E_RCON_PORT ?? 25632),
        password: process.env.E2E_RCON_PASSWORD ?? 'smpeco', timeout: 30000 })
      console.log('[E2E] ' + (await r.send('smpeco starterkit enable')).replace(/§[0-9a-fk-or]/gi, ''))
      await r.end()
    } catch (err) {
      console.log(`[E2E] WARN smpeco starterkit enable failed: ${err.message}`)
    }
  }
}

console.log('\n=== Result ===')
let totalPassed = 0
let totalFailed = 0
for (const r of results) {
  const ok = r.code === 0 && r.failed === 0 && r.summary
  totalPassed += r.passed
  totalFailed += r.failed
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${r.file}: passed=${r.passed} failed=${r.failed} exit=${r.code}${r.summary ? '' : ' (no SUMMARY)'}`)
}
console.log(`[E2E] TOTAL files=${results.length} passed=${totalPassed} failed=${totalFailed}`)
if (skippedForModules.length > 0) console.log(`[E2E] SKIPPED (module off): ${skippedForModules.join(', ')}`)
process.exitCode = results.every((r) => r.code === 0 && r.failed === 0 && r.summary) ? 0 : 1
