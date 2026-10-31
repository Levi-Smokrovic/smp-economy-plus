// Module system with real restarts: config/smpeconomy/modules.json decides which modules run, and changes apply
// after a restart. Covers defaults (core on, extras off), enabling by file and by command, the refused changes
// (economy off, auction off while market is on, market without auction), and restoring the original
// modules.json, since the rest of the suite expects all modules on.
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { connectBot, rcon, closeRcon, assert, assertEq, runE2E, sleep, expectWindow, windowTitle, itemLabel } from './lib.mjs'
import { restartServer, E2E_SERVER_DIR } from '../tools/server-restart.mjs'

const FILE = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'modules.json')
const BOT = 'BotModulesA'
const CORE = ['economy', 'starter', 'sell', 'shop', 'auction', 'jobs', 'tasks', 'hud', 'orders', 'settings', 'stats', 'holo', 'missions',
  'bank', 'menu', 'npc']
const EXTRA = ['minigames', 'market', 'daily', 'bounty', 'crates', 'afk', 'events', 'news', 'casino', 'clans']

function moduleFile(overrides = {}) {
  const obj = { _info: 'e2e modules.mjs' }
  for (const id of CORE) obj[id] = true
  for (const id of EXTRA) obj[id] = false
  return JSON.stringify({ ...obj, ...overrides }, null, 2) + '\n'
}

async function restart(label) {
  await closeRcon()
  await restartServer({ log: (m) => console.log(`[E2E] INFO ${label}: ${m}`) })
}

/** RCON may run anything; /help calls unknown commands "Unknown command". */
async function commandExists(root) {
  const out = await rcon(`help ${root}`)
  return !/Unknown command|Unknown or incomplete|Incorrect argument/i.test(out) && out.trim().length > 0
}

function kv(line) {
  const out = {}
  for (const m of line.matchAll(/(\w+)=(\S*)/g)) out[m[1]] = m[2]
  return out
}

async function listState() {
  const out = await rcon('smpeco module list')
  const first = out.split('\n')[0]
  // RCON joins lines without a line break, so parse values strictly
  const running = first.match(/running=([a-z,]*)/)?.[1] ?? ''
  const configured = first.match(/configured=([a-z,]*)/)?.[1] ?? ''
  const pending = Number(first.match(/pending=(\d+)/)?.[1] ?? -1)
  return { running: running.split(',').filter(Boolean), configured: configured.split(',').filter(Boolean), pending, raw: out }
}

async function menuLabels() {
  const bot = await connectBot(BOT)
  await sleep(500)
  const win = await expectWindow(bot, () => bot.chat('/menu'))
  const labels = []
  for (let i = 0; i < win.inventoryStart; i++) {
    const it = win.slots[i]
    if (it && !/stained_glass_pane/.test(it.name)) labels.push(itemLabel(it))
  }
  const title = windowTitle(win)
  bot.closeWindow(win)
  await sleep(300)
  bot.quit()
  await sleep(1000)
  return { title, labels }
}

runE2E(async () => {
  const original = existsSync(FILE) ? readFileSync(FILE, 'utf8') : null
  try {
    writeFileSync(FILE, moduleFile())
    await restart('Defaults')
    let st = await listState()
    assertEq(st.running, CORE, 'defaults: exactly the core modules run')
    assertEq(st.pending, 0, 'defaults: no restart pending')
    assert(!(await commandExists('casino')), '/casino does not exist (casino off)')
    assert(!(await commandExists('lottery')), '/lottery does not exist (events off)')
    assert(!(await commandExists('crates')), '/crates does not exist (crates off)')
    assert(await commandExists('shop'), '/shop exists (CORE)')
    const selftest = await rcon('smpeco selftest modules')
    assert(/SUMMARY passed=\d+ failed=0/.test(selftest), `selftests modules with defaults (${selftest.slice(-80)})`)
    const adminTests = await rcon('smpeco selftest admin')
    assert(/SUMMARY passed=\d+ failed=0/.test(adminTests), `Selftests admin (${adminTests.slice(-80)})`)
    const info = kv(await rcon('smpeco module info casino'))
    assertEq([info.enabled, info.commands], ['false', ''], 'module info casino: off, no commands')
    let menu = await menuLabels()
    assert(/Main Menu/.test(menu.title), `/menu opens (${menu.title})`)
    assert(!menu.labels.includes('Casino'), 'menu without casino icon')
    assert(menu.labels.includes('Shop'), 'menu with shop icon')

    writeFileSync(FILE, moduleFile({ casino: true }))
    await restart('casino by file')
    st = await listState()
    assert(st.running.includes('casino'), 'casino runs after restart')
    assert(await commandExists('casino'), '/casino exists now')
    assert(await commandExists('blackjack'), '/blackjack exists now')
    assert(!(await commandExists('lottery')), '/lottery still absent')
    const casinoInfo = kv(await rcon('smpeco module info casino'))
    assert(casinoInfo.enabled === 'true' && casinoInfo.commands.split(',').includes('casino'), `module info casino: on, with /casino (${casinoInfo.commands})`)
    menu = await menuLabels()
    assert(menu.labels.includes('Casino'), 'menu with casino icon')

    const enable = await rcon('smpeco module enable bounty')
    assert(/bounty enabled/.test(enable) && /restart/i.test(enable), `enable bounty: ${enable.trim()}`)
    assert(JSON.parse(readFileSync(FILE, 'utf8')).bounty === true, 'modules.json: bounty = true')
    st = await listState()
    assert(!st.running.includes('bounty') && st.configured.includes('bounty') && st.pending === 1, 'bounty: configured, not running yet (restart pending)')
    assert(!(await commandExists('bounty')), '/bounty not there before the restart')

    const eco = await rcon('smpeco module disable economy')
    assert(/cannot be disabled/.test(eco), `disabling economy refused (${eco.trim()})`)
    const mkt = await rcon('smpeco module enable market')
    assert(/market enabled/.test(mkt), `enabling market (auction is on): ${mkt.trim()}`)
    const auc = await rcon('smpeco module disable auction')
    assert(/market needs auction/.test(auc), `disabling auction refused while market is on (${auc.trim()})`)
    const both = await rcon('smpeco module disable auction with-dependents')
    assert(/auction, market disabled|market, auction disabled/.test(both), `auction disabled with dependents (${both.trim()})`)
    const lone = await rcon('smpeco module enable market')
    assert(/requires auction/.test(lone), `market without auction refused (${lone.trim()})`)
    const fix = await rcon('smpeco module enable auction')
    assert(/auction enabled/.test(fix), 'auction on again')

    await restart('bounty by command')
    st = await listState()
    assert(st.running.includes('bounty') && st.pending === 0, 'bounty runs after restart')
    assert(await commandExists('bounty'), '/bounty exists now')
    assert(st.running.includes('auction') && !st.running.includes('market'), 'auction on, market off')
  } finally {
    if (original !== null) writeFileSync(FILE, original)
    else writeFileSync(FILE, moduleFile(Object.fromEntries(EXTRA.map((id) => [id, true]))))
    await restart('initial state')
    const st = await listState()
    assert(st.pending === 0, `initial state restored (${st.running.length} modules running)`)
  }
}, { timeoutMs: 900000 })
