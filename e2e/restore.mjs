// Start refusal and restore of mod data on a real server start, unlike the selftest core/datastore-restore,
// which uses a test folder inside the running server. Restarts the server four times (2 to 4 minutes). (0)
// Clean stop, copy <world>/smpeconomy; the copy is always restored at the end. (1) Overwrite economy.json with
// "{": the start aborts, the process exits, no file changes, no new restore point. (2) Delete economy.json
// while restore points exist: also aborts, and the file is not recreated with defaults. (3) RESTORE_ALLOWED:
// the start succeeds, all stores come from the newest restore point, the permission file and journal are gone,
// old files move to backups/replaced-<time>/. (4) Original data back, normal start.
import { cpSync, existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { assert, assertEq, closeRcon, rcon, runE2E } from './lib.mjs'
import { E2E_SERVER_DIR, serverPid, startServer, stopServer } from '../tools/server-restart.mjs'

const levelName = (readFileSync(join(E2E_SERVER_DIR, 'server.properties'), 'utf8').match(/^level-name=(.*)$/m)?.[1] ?? 'world').trim() || 'world'
const DATA = join(E2E_SERVER_DIR, levelName, 'smpeconomy')
const BACKUPS = join(DATA, 'backups')
const SNAPSHOT = /^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{3}(-\d+)?$/
const SYSTEM_ACCOUNT = /^00000000-0000-0000-0000-/
const OTHER_STORES = ['players.json', 'auctions.json', 'treasury.json', 'orders.json']

const snapshots = () => (existsSync(BACKUPS) ? readdirSync(BACKUPS).filter((n) => SNAPSHOT.test(n)).sort() : [])
const bytes = (file) => (existsSync(file) ? readFileSync(file) : null)
const same = (a, b) => (a === null || b === null ? a === b : Buffer.compare(a, b) === 0)

async function money(name) {
  const m = (await rcon(`eco get ${name}`)).match(/\((\d+) cents\)/)
  return m ? Number(m[1]) : null
}

async function expectRefusal(what, check) {
  const before = snapshots()
  const others = Object.fromEntries(OTHER_STORES.map((f) => [f, bytes(join(DATA, f))]))
  const r = await startServer({ expectFailure: true })
  assert(/Server start aborted/.test(r.log), `${what}: start refused`)
  assert(r.exited, `${what}: process exits after the refusal (otherwise the server hangs without a tick loop)`)
  assert(!/RESTORED: all/.test(r.log), `${what}: nothing reset`)
  assertEq(snapshots(), before, `${what}: no new restore point`)
  assert(!existsSync(join(DATA, 'COMMIT_RUNNING')), `${what}: no save journal`)
  for (const f of OTHER_STORES) assert(same(bytes(join(DATA, f)), others[f]), `${what}: ${f} unchanged (nothing saved on refusal)`)
  check(r.log)
}

runE2E(async () => {
  assert(!!serverPid(), 'server running')
  await closeRcon()
  await stopServer()
  const saved = mkdtempSync(join(tmpdir(), 'smpeco-restore-e2e-'))
  const copy = join(saved, 'smpeconomy')
  cpSync(DATA, copy, { recursive: true })
  const snapsBefore = snapshots()
  assert(snapsBefore.length > 0, `restore points exist (${snapsBefore.length})`)
  const newest = snapsBefore[snapsBefore.length - 1]
  try {
    // (1) corrupt
    writeFileSync(join(DATA, 'economy.json'), '{')
    await expectRefusal('corrupt economy.json', (log) => {
      assert(/economy\.json is damaged/.test(log), 'log names the corrupt file')
      assertEq(readFileSync(join(DATA, 'economy.json'), 'utf8'), '{', 'corrupt file identical byte for byte')
    })

    // (2) missing although restore points exist
    unlinkSync(join(DATA, 'economy.json'))
    await expectRefusal('missing economy.json', (log) => {
      assert(/economy\.json is missing although traces of it exist/.test(log), 'log: missing despite traces')
      assert(!existsSync(join(DATA, 'economy.json')), 'missing file not created with defaults')
    })

    // (3) permission: all stores from the newest restore point
    const snapEco = JSON.parse(readFileSync(join(BACKUPS, newest, 'economy.json'), 'utf8'))
    const accounts = Object.entries(snapEco.accounts ?? {})
      .filter(([id, a]) => a && !SYSTEM_ACCOUNT.test(id) && /^[A-Za-z0-9_]{3,16}$/.test(a.name ?? ''))
      .slice(0, 5)
    writeFileSync(join(DATA, 'RESTORE_ALLOWED'), '')
    const r = await startServer()
    assert(/RESTORED: all/.test(r.log), 'restore in the log')
    assert(r.log.includes(`restore point ${newest}`), `from the newest restore point ${newest}`)
    assert(!existsSync(join(DATA, 'RESTORE_ALLOWED')), 'permission file deleted')
    assert(!existsSync(join(BACKUPS, 'RESTORE_RUNNING')), 'restore journal deleted')
    assert(readdirSync(BACKUPS).some((n) => n.startsWith('replaced-')), 'old files in backups/replaced-<time>/')
    assert(accounts.length > 0, `accounts in the restore point (${accounts.length})`)
    for (const [, a] of accounts) assertEq(await money(a.name), a.balance, `balance ${a.name} = restore point`)
    const status = await rcon('smpeco status')
    assert(/persistence=ok/.test(status), `saving ok after the restore (${status.split('\n')[0]})`)
  } finally {
    // (4) original data back
    await closeRcon()
    if (serverPid()) await stopServer()
    rmSync(DATA, { recursive: true, force: true })
    cpSync(copy, DATA, { recursive: true })
    await startServer()
    rmSync(saved, { recursive: true, force: true })
  }
  assert(/balance=\d+/.test(await rcon('smpeco treasury')), 'server running again with the original data')
}, { timeoutMs: 25 * 60000 })
