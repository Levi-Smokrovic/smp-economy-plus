// Starter kit and starting balance. An admin bot captures items from its inventory (hotbar, head) with "/smpeco
// starterkit capture". A random-named bot is a really new player and gets the kit in the same slots, greeting
// and balance on first join; a rejoin gets nothing, "reset" makes it due again, "give" hands it out at once,
// "disable" stops it. starter.json and the balance are restored at the end.
import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage } from './lib.mjs'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'

const CONFIG = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'starter.json')
const ADMIN = 'stkAdmin1'
const NEW = 'stk' + Date.now().toString(36).slice(-8) // never seen before

function count(bot, name) {
  return bot.inventory.items().filter((i) => i.name === name).reduce((s, i) => s + i.count, 0)
}

async function cents(name) {
  const out = await rcon(`eco get ${name}`)
  const m = out.match(/\((\d+) cents\)/)
  if (!m) throw new Error(`eco get ${name}: ${out}`)
  return Number(m[1])
}

async function show() {
  const out = await rcon('smpeco starterkit show')
  const m = out.match(/starterkit giveKit=(\w+) onlyNewPlayers=(\w+) kitVersion=(\d+) items=(\d+) startBalance=(\d+)/)
  if (!m) throw new Error(`starterkit show: ${out}`)
  return { giveKit: m[1] === 'true', onlyNew: m[2] === 'true', version: Number(m[3]), items: Number(m[4]), startBalance: Number(m[5]), raw: out }
}

async function joinBot(name, { welcome = false } = {}) {
  const bot = await connectBot(name, {
    setup: (b) => {
      b.welcomeSeen = waitForMessage(b, /starter kit/i, 8000).then(() => true).catch(() => false)
    },
  })
  await sleep(1500) // inventory sync after the join
  if (welcome) assert(await bot.welcomeSeen, `${name}: greeting with the kit`)
  return bot
}

async function scenario() {
  const admin = await connectBot(ADMIN)
  await rcon(`clear ${ADMIN}`)
  await rcon(`item replace entity ${ADMIN} hotbar.0 with minecraft:diamond 3`)
  await rcon(`item replace entity ${ADMIN} hotbar.4 with minecraft:cooked_beef 7`)
  await rcon(`item replace entity ${ADMIN} armor.head with minecraft:golden_helmet`)
  await sleep(500)
  const cap = await rcon(`execute as ${ADMIN} run smpeco starterkit capture`)
  assert(/Starter kit saved: 3 stacks/.test(cap), `capture: ${cap.trim()}`)
  await rcon(`clear ${ADMIN}`)
  admin.quit()
  assert(/New players get the starter kit/.test(await rcon('smpeco starterkit enable')), 'enable')
  assert(/Only new players/.test(await rcon('smpeco starterkit onlynew true')), 'onlynew true')
  const bal = await rcon('smpeco starterkit balance 123.45')
  assert(/start with \$123\.45/.test(bal), `balance: ${bal.trim()}`)
  const s = await show()
  assertEq([s.giveKit, s.onlyNew, s.items, s.startBalance], [true, true, 3, 12345], 'show: on, new players only, 3 stacks, $123.45')
  assert(/hotbar 0 .*3x Diamond/.test(s.raw) && /head .*Golden Helmet/.test(s.raw), 'show lists slots and items')

  let bot = await joinBot(NEW, { welcome: true })
  assertEq(count(bot, 'diamond'), 3, 'first join: 3 diamonds')
  assertEq(count(bot, 'cooked_beef'), 7, 'first join: 7 steak')
  assertEq(bot.inventory.slots[36]?.name, 'diamond', 'diamonds in hotbar 0')
  assertEq(bot.inventory.slots[40]?.name, 'cooked_beef', 'Steak in Hotbar 4')
  assertEq(bot.inventory.slots[5]?.name, 'golden_helmet', 'helmet on the head')
  assertEq(await cents(NEW), 12345, 'starting balance $123.45')
  bot.quit()
  await sleep(1500)

  bot = await joinBot(NEW)
  assertEq(count(bot, 'diamond'), 3, 'rejoin: still 3 diamonds (no second kit)')
  assertEq(await cents(NEW), 12345, 'rejoin: balance unchanged')
  bot.quit()
  await sleep(1500)

  const reset = await rcon(`smpeco starterkit reset ${NEW}`)
  assert(/gets the starter kit again/.test(reset), `reset (offline): ${reset.trim()}`)
  bot = await joinBot(NEW)
  assertEq(count(bot, 'diamond'), 6, 'after reset: kit once more (6 diamonds)')

  const give = await rcon(`smpeco starterkit give ${NEW}`)
  assert(/Starter kit given to/.test(give), `give: ${give.trim()}`)
  await sleep(800)
  assertEq(count(bot, 'diamond'), 9, 'give: 9 diamonds')
  bot.quit()
  await sleep(1500)

  assert(/switched off/.test(await rcon('smpeco starterkit disable')), 'disable')
  await rcon(`smpeco starterkit reset ${NEW}`)
  bot = await joinBot(NEW)
  assertEq(count(bot, 'diamond'), 9, 'kit off: nothing new')
  bot.quit()

  const unknown = await rcon('smpeco starterkit reset nobody_xyz_123')
  assert(/Unknown player/.test(unknown), 'reset unknown player')
  await rcon(`clear ${NEW}`).catch(() => {})
}

runE2E(async () => {
  const original = existsSync(CONFIG) ? readFileSync(CONFIG, 'utf8') : null
  const before = await show()
  try {
    await scenario()
  } finally {
    try {
      if (original != null) writeFileSync(CONFIG, original)
      else unlinkSync(CONFIG)
      await rcon('smpeco reload')
      await rcon(`smpeco starterkit balance ${(before.startBalance / 100).toFixed(2)}`)
      const after = await show()
      assertEq([after.items, after.startBalance], [before.items, before.startBalance], 'cleaned up: kit and starting balance as before')
    } catch (err) {
      console.log(`[E2E] INFO cleanup failed: ${err?.message ?? err}`)
    }
  }
}, { timeoutMs: 300000 })
