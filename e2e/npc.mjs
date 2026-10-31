// Admin-placed NPCs (mannequin plus name plate), set up at the bot's position with "execute as <bot> at <bot>".
// npc.json is saved and restored. Covers create with template and skin, exactly one entity per part, right
// click opens the bank as the player, a command NPC, a target of a disabled module, rename, move, set look,
// left click, removal of a /summon copy, remove, and a restart when nobody else is online and E2E_NO_RESTART is
// not 1.
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  connectBot, rcon, closeRcon, assert, assertEq, sleep, runE2E, waitForMessage, windowTitle, expectWindow,
} from './lib.mjs'
import { E2E_SERVER_DIR, restartServer } from '../tools/server-restart.mjs'

const CONFIG = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'npc.json')
const BOT = 'npcBot1'
const X = 4800.5, Y = 200, Z = -4800.5
const ALL_HERE = `@e[tag=smpeco_npc,x=${X},y=${Y},z=${Z},distance=..40]`
const byKey = (key, extra = '') => `@e[tag=smpeco_npc_${key}${extra}]`
const TEST_IDS = ['e2e_bank', 'e2e_cmd', 'e2e_off']

async function count(selector) {
  const out = await rcon(`execute if entity ${selector}`)
  const m = out.match(/count:?\s*(\d+)/i)
  if (m) return Number(m[1])
  if (/failed/i.test(out)) return 0
  throw new Error(`execute if entity ${selector}: ${out}`)
}

async function waitFor(fn, timeoutMs, stepMs = 500) {
  const end = Date.now() + timeoutMs
  let last
  while (Date.now() < end) {
    last = await fn()
    if (last) return last
    await sleep(stepMs)
  }
  return last
}

async function exactlyOne(ids, label) {
  const ok = await waitFor(async () => (await count(ALL_HERE)) === ids.length * 2, 15000)
  assert(ok, `${label}: ${ids.length * 2} NPC entities (is ${await count(ALL_HERE)})`)
  const wrong = []
  for (const id of ids) {
    for (const part of ['body', 'plate']) {
      const c = await count(byKey(`n.${id}.${part}`))
      if (c !== 1) wrong.push(`${id}.${part}=${c}`)
    }
  }
  assert(wrong.length === 0, `${label}: exactly one per part${wrong.length ? ', ' + wrong.join(', ') : ''}`)
}

async function entityPos(key) {
  const out = await rcon(`data get entity ${byKey(key, ',limit=1')} Pos`)
  const m = out.match(/\[(-?[\d.]+)d, (-?[\d.]+)d, (-?[\d.]+)d\]/)
  return m ? m.slice(1, 4).map(Number) : null
}

function nearestEntity(bot, name, x, y, z) {
  let best = null, bestD = 2
  for (const e of Object.values(bot.entities)) {
    if (e.name !== name) continue
    const d = Math.hypot(e.position.x - x, e.position.y - y, e.position.z - z)
    if (d < bestD) { best = e; bestD = d }
  }
  return best
}

async function tpBot(x, y, z, yaw = 0) {
  await rcon(`tp ${BOT} ${x} ${y} ${z} ${yaw} 0`)
  await sleep(700)
}

const asBot = (cmd) => rcon(`execute as ${BOT} at ${BOT} run smpeco npc ${cmd}`)

async function clickExpectWindow(bot, entity, titleRe, label) {
  await sleep(700) // click cooldown (10 ticks) and window debounce
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await bot.lookAt(entity.position.offset(0, 1, 0), true)
  const win = await expectWindow(bot, () => bot.activateEntity(entity)).catch((e) => e)
  const title = win instanceof Error ? `(no window: ${win.message})` : windowTitle(win)
  assert(titleRe.test(title), `${label}: "${title}"`)
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await sleep(300)
}

async function clickExpectMessage(bot, entity, re, label) {
  await sleep(700)
  if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
  await bot.lookAt(entity.position.offset(0, 1, 0), true)
  const msg = waitForMessage(bot, re, 5000).catch(() => null)
  bot.activateEntity(entity)
  assert(!!(await msg), label)
}

async function othersOnline() {
  const out = await rcon('list')
  const m = out.match(/online:\s*(.*)$/s)
  const names = m ? m[1].split(',').map((s) => s.trim()).filter(Boolean) : []
  return names.filter((n) => n !== BOT)
}

async function scenario() {
  // block commands need whole numbers
  const XI = Math.floor(X), ZI = Math.floor(Z)
  await rcon(`forceload add ${XI - 16} ${ZI - 16} ${XI + 16} ${ZI + 16}`)
  const loaded = await waitFor(async () => /Successfully|No blocks were filled/i.test(
    await rcon(`fill ${XI - 12} ${Y - 1} ${ZI - 8} ${XI + 12} ${Y - 1} ${ZI + 8} minecraft:smooth_stone`)), 60000, 1000)
  assert(!!loaded, 'test area loaded and built')
  await rcon(`fill ${XI - 12} ${Y} ${ZI - 8} ${XI + 12} ${Y + 4} ${ZI + 8} minecraft:air`)
  await rcon(`forceload remove ${XI - 16} ${ZI - 16} ${XI + 16} ${ZI + 16}`)
  for (const id of TEST_IDS) await rcon(`smpeco npc remove ${id}`)

  let bot = await connectBot(BOT)
  await rcon(`gamemode survival ${BOT}`)
  await rcon(`deop ${BOT}`)

  await tpBot(X + 2, Y, Z, 90)
  const created = await asBot('create e2e_bank bank slim/makena')
  assert(/NPC e2e_bank: created at overworld 4802\.50,200\.00,-4800\.50 facing 90/.test(created), `create bank: ${created.trim()}`)
  const dup = await asBot('create e2e_bank shop')
  assert(/already used/.test(dup), `duplicate ID refused: ${dup.trim()}`)
  const badAction = await asBot('create e2e_x nonsense')
  assert(/Unknown action/.test(badAction), `unknown action refused: ${badAction.trim()}`)
  await tpBot(X - 2, Y, Z, -90)
  assert(/created/.test(await asBot('create e2e_cmd command Notch')), 'create command-NPC')
  const setCmd = await rcon('smpeco npc set e2e_cmd action command balance')
  assert(/now runs a command/.test(setCmd), `set action command: ${setCmd.trim()}`)

  // target of a disabled module; only possible through npc.json, since create refuses it
  const help = await rcon('smpeco npc')
  const avail = (help.match(/Actions: (.*?), command, none/) ?? [])[1]?.split(',').map((s) => s.trim()) ?? []
  const all = ['shop', 'sell', 'ah', 'orders', 'bank', 'jobs', 'missions', 'tasks', 'daily', 'crates', 'casino', 'lottery', 'event', 'bounty',
    'clan', 'market', 'coinflip', 'stats', 'news', 'settings', 'menu']
  const offTarget = all.find((t) => !avail.includes(t))
  if (offTarget) {
    const rej = await asBot(`set e2e_cmd action ${offTarget}`)
    assert(/isn't enabled/.test(rej), `set action ${offTarget} (module off) refused: ${rej.trim()}`)
    const cfg = JSON.parse(readFileSync(CONFIG, 'utf8'))
    cfg.npcs.push({ id: 'e2e_off', dimension: 'minecraft:overworld', x: X, y: Y, z: Z + 2, yaw: 180, name: '§cOff', description: '', skin: '',
      equipment: {}, action: { type: 'open', target: offTarget, arg: '', command: '' }, messages: [], lookAtPlayer: true })
    writeFileSync(CONFIG, JSON.stringify(cfg, null, 2))
    const rel = await rcon('smpeco npc reload')
    assert(/NPCs reloaded/.test(rel), `reload with e2e_off: ${rel.trim()}`)
  } else {
    console.log('[E2E] INFO all modules running, "module off" test skipped')
  }
  const ids = offTarget ? TEST_IDS : TEST_IDS.slice(0, 2)

  await tpBot(X, Y, Z, 0)
  const list = await waitFor(async () => {
    const l = await rcon('smpeco npc list')
    return ids.every((id) => new RegExp(`npc ${id} .* live=true`).test(l)) ? l : null
  }, 15000)
  assert(!!list, 'npc list: all test NPCs live')
  assert(/npc e2e_bank overworld 4802\.50,200\.00,-4800\.50 action=open:bank look=true/.test(list ?? ''), 'list: position and action bank')
  assert(/npc e2e_cmd .* action=command:balance/.test(list ?? ''), 'list: action command')
  await exactlyOne(ids, 'first placement')

  const nbt = await rcon(`data get entity ${byKey('n.e2e_bank.body', ',limit=1')}`)
  assert(/Invulnerable: 1b/.test(nbt) && /NoGravity: 1b/.test(nbt) && /immovable: 1b/.test(nbt), 'mannequin invulnerable, floating, immovable')
  assert(/texture: "minecraft:entity\/player\/slim\/makena"/.test(nbt), 'skin from the create command')
  assert(/gold_block/.test(nbt), 'equipment from the template (gold block)')
  const plate = await rcon(`data get entity ${byKey('n.e2e_bank.plate', ',limit=1')} text`)
  assert(/Banker/.test(plate) && /interest/.test(plate), `name plate: ${plate.slice(0, 120)}`)

  await sleep(1000)
  const bankNpc = nearestEntity(bot, 'mannequin', X + 2, Y, Z)
  const cmdNpc = nearestEntity(bot, 'mannequin', X - 2, Y, Z)
  assert(bankNpc && cmdNpc, 'bot sees the mannequins')
  if (bankNpc) await clickExpectWindow(bot, bankNpc, /Bank/, 'right click banker → /bank')
  if (cmdNpc) await clickExpectMessage(bot, cmdNpc, /Balance/i, 'command NPC runs /balance as the player')
  if (offTarget) {
    const offNpc = nearestEntity(bot, 'mannequin', X, Y, Z + 2)
    if (offNpc) await clickExpectMessage(bot, offNpc, /That isn't available on this server/, `NPC with target ${offTarget} (module off) gives a message`)
    else assert(false, 'bot sees the e2e_off NPC')
  }
  if (bankNpc) {
    const hp = await rcon(`data get entity ${byKey('n.e2e_bank.body', ',limit=1')} Health`)
    await sleep(700)
    await bot.lookAt(bankNpc.position.offset(0, 1, 0), true)
    const win = await expectWindow(bot, () => bot.attack(bankNpc), 2000).catch(() => null)
    assert(win == null, 'left click on NPC opens nothing')
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    await sleep(500)
    assertEq(await rcon(`data get entity ${byKey('n.e2e_bank.body', ',limit=1')} Health`), hp, 'health unchanged after attack')
  }

  const ren = await rcon('smpeco npc rename e2e_bank &6Money Man')
  assert(/renamed/.test(ren), `rename: ${ren.trim()}`)
  const renamed = await waitFor(async () => /Money Man/.test(await rcon(`data get entity ${byKey('n.e2e_bank.plate', ',limit=1')} text`)), 5000)
  assert(renamed, 'plate shows the new name')
  assert(/look-at-player off/.test(await rcon('smpeco npc set e2e_cmd look false')), 'set look false')
  assert(/action=command:balance look=false/.test(await rcon('smpeco npc list')), 'list: look=false')
  await tpBot(X - 4, Y, Z + 4, 0)
  assert(/moved to/.test(await asBot('move e2e_cmd')), 'move e2e_cmd')
  const moved = await waitFor(async () => {
    const p = await entityPos('n.e2e_cmd.body')
    return p && Math.abs(p[0] - (X - 4)) < 0.01 && Math.abs(p[2] - (Z + 4)) < 0.01
  }, 5000)
  assert(moved, 'entity at the new position')
  await exactlyOne(ids, 'after rename/move')

  await rcon(`summon minecraft:mannequin ${X} ${Y} ${Z - 3} {Tags:["smpeco_npc","smpeco_npc_n.e2e_bank.body"]}`)
  await sleep(300)
  await exactlyOne(ids, '/summon copy removed')

  const rem = await rcon('smpeco npc remove e2e_bank')
  assert(/removed/.test(rem), `remove: ${rem.trim()}`)
  const left = ids.filter((id) => id !== 'e2e_bank')
  await exactlyOne(left, 'after remove')
  assertEq(await count(byKey('n.e2e_bank.body')), 0, 'banker body gone')
  assert(!/npc e2e_bank /.test(await rcon('smpeco npc list')), 'list without e2e_bank')
  assert(!JSON.parse(readFileSync(CONFIG, 'utf8')).npcs.some((n) => n.id === 'e2e_bank'), 'npc.json without e2e_bank')

  const others = await othersOnline()
  if (process.env.E2E_NO_RESTART === '1' || others.length > 0) {
    console.log(`[E2E] INFO restart skipped (${others.length ? 'online: ' + others.join(', ') : 'E2E_NO_RESTART=1'})`)
    return
  }
  bot.quit()
  await closeRcon()
  await restartServer()
  bot = await connectBot(BOT)
  await tpBot(X, Y, Z)
  await exactlyOne(left, 'after restart')
  assertEq(await count(byKey('n.e2e_bank.body')), 0, 'after restart: removed NPC stays gone')
  await sleep(1000)
  const cmd2 = nearestEntity(bot, 'mannequin', X - 4, Y, Z + 4)
  if (cmd2) await clickExpectMessage(bot, cmd2, /Balance/i, 'after restart: command NPC clickable')
  else assert(false, 'after restart: bot sees the command NPC')
}

runE2E(async () => {
  const original = existsSync(CONFIG) ? readFileSync(CONFIG, 'utf8') : null
  try {
    await scenario()
  } finally {
    try {
      if (original != null) writeFileSync(CONFIG, original)
      else unlinkSync(CONFIG)
      await rcon('smpeco npc reload')
      await sleep(1500)
      assertEq(await count(ALL_HERE), 0, 'cleaned up: no test NPCs left')
    } catch (err) {
      console.log(`[E2E] INFO cleanup failed: ${err?.message ?? err}`)
    }
  }
}, { timeoutMs: 600000 })
