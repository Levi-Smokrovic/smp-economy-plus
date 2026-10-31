// Foundation: selftests over RCON, pending messages, price pipeline (PriceModifiers to PriceGuard), treasury,
// Gui helpers (pagination, confirm dialog, frame, no item leaks) and PlacedBlockTracker through the real mixins
// (placing, falling, pistons, breaking).
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, waitForWindow, clickSlot, findSlot,
  itemLabel, itemLore, windowTitle, expectWindow, Vec3,
} from './lib.mjs'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'

const NAME = 'BotFoundation'
const X = 4000, Y = 200, Z = 4000 // test area far from spawn (spawn protection)

async function placed(x, y, z) {
  const out = await rcon(`smpeco placed ${x} ${y} ${z}`)
  const m = out.match(/placed=(true|false)/)
  return m ? m[1] === 'true' : `?? ${out}`
}

async function price(item) {
  const out = await rcon(`smpeco price ${item}`)
  const m = out.match(/sell=(-?\d+) buy=(-?\d+)/)
  if (!m) throw new Error(`smpeco price ${item}: ${out}`)
  const pivot = out.match(/pivot=(-?\d+)/)
  return { sell: Number(m[1]), buy: Number(m[2]), pivot: pivot ? Number(pivot[1]) : -1, raw: out }
}

async function equip(bot, itemName) {
  for (let i = 0; i < 20 && !bot.inventory.items().some((it) => it.name === itemName); i++) await sleep(100)
  const item = bot.inventory.items().find((it) => it.name === itemName)
  if (!item) throw new Error(`${itemName} not in inventory`)
  await bot.equip(item, 'hand')
}

async function placeOnTop(bot, x, y, z) {
  await bot.placeBlock(bot.blockAt(new Vec3(x, y, z)), new Vec3(0, 1, 0))
  await sleep(300)
}

runE2E(async () => {
  const bot = await connectBot(NAME)
  assert(true, 'bot connected')

  const out = await rcon('smpeco selftest')
  const m = out.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!m, `selftest summary present (${out.slice(0, 300)})`)
  if (m) {
    assert(Number(m[1]) > 0, `selftests ran (passed=${m[1]})`)
    assertEq(Number(m[2]), 0, 'Selftests failed=0')
  }
  const bad = await rcon('smpeco selftest nonexistent')
  assert(/No tests/.test(bad), 'unknown feature refused')
  const core = await rcon('smpeco selftest core')
  assert(/SUMMARY passed=\d+ failed=0/.test(core), 'selftests for "core" only')

  // admin commands reach the server log with their sender ([SMPEco-admin])
  const logFile = join(E2E_SERVER_DIR, 'logs', 'latest.log')
  if (existsSync(logFile)) {
    const tag = `e2e${Date.now() % 1000000}`
    await rcon(`smpeco tasks info ${NAME}`)
    await rcon(`execute run smpeco tasks info ${NAME}`)
    await rcon(`smpeco placed ${X} ${Y} ${Z}`)
    await rcon(`smpeco tasks nonexistent ${tag}`)
    let log = ''
    for (let i = 0; i < 20; i++) {
      log = readFileSync(logFile, 'utf8')
      if (log.includes(`/smpeco placed ${X} ${Y} ${Z}`)) break
      await sleep(250)
    }
    assert(log.includes(`[SMPEco-admin] Rcon (smpeconomy.admin.core, smpeconomy.admin.tasks): /smpeco tasks info ${NAME}`), 'log: /smpeco tasks info with sender and permission')
    assert(log.includes(`[SMPEco-admin] Rcon (smpeconomy.admin.core, smpeconomy.admin.tasks): /execute run smpeco tasks info ${NAME}`), 'log: also behind /execute run')
    assert(log.includes(`[SMPEco-admin] Rcon (smpeconomy.admin.core): /smpeco placed ${X} ${Y} ${Z}`), 'log: /smpeco subcommand')
    assert(!log.includes(`/smpeco tasks nonexistent ${tag}`), 'log: syntax error produces no line')
    const status = await rcon('smpeco status')
    assert(/placed=ok/.test(status), `PlacedBlockTracker reliable (${status.split('\n')[0]})`)
    if (/placed=ok/.test(status)) {
      assert(/Nothing to confirm/.test(await rcon('smpeco placed confirm')), '/smpeco placed confirm')
    }
  } else {
    console.log(`[E2E] INFO ${logFile} missing, admin log check skipped`)
  }

  await rcon('smpeco pricemod clear')
  const base = await price('minecraft:stone')
  assert(base.sell > 0 && base.buy > 0, `stone has sell and shop price (${base.raw.trim()})`)
  assert(base.buy * 4 >= base.sell * 5, 'stone: shop >= 1.25 x sell')
  await rcon('smpeco pricemod minecraft:stone 3 5')
  const hype = await price('minecraft:stone')
  // shop items: selling stops at the pivot (0.6 x base buy price), so buying before the hype never pays
  assertEq(hype.sell, base.pivot >= 0 ? Math.min(base.sell * 3, base.pivot) : base.sell * 3, `hype x3 on the sell price (pivot ${base.pivot})`)
  assert(base.pivot < 0 || (hype.sell <= base.pivot && hype.buy > base.pivot), 'pivot separates sell and buy')
  assert(hype.buy >= base.buy * 3 - 1 && hype.buy * 4 >= hype.sell * 5, `hype x3 on the shop price (${hype.raw.trim()})`)
  assert(/\+200% Admin/.test(hype.raw), 'modifier shows in the lore lines')
  await rcon('smpeco pricemod minecraft:stone 0.5 5')
  const crash = await price('minecraft:stone')
  assertEq(crash.sell, Math.floor(base.sell * 0.5), 'crash -50 % on the sell price')
  assert(crash.buy > crash.sell, 'crash: buying and selling stays unprofitable')
  assertEq(crash.buy, base.buy, 'crash does not lower the shop price')
  await rcon('smpeco pricemod clear')
  const back = await price('minecraft:stone')
  assertEq([back.sell, back.buy], [base.sell, base.buy], 'base prices again after clear')

  const t = await rcon('smpeco treasury')
  const tm = t.match(/balance=(\d+) allowance=(\d+) spentLastHour=(\d+)/)
  assert(!!tm, `treasury queryable (${t.slice(0, 120)})`)
  if (tm) {
    assert(Number(tm[1]) > 0, 'treasury has starting funds')
    assert(Number(tm[2]) <= Number(tm[1]) * 0.05 + 1, 'hourly budget <= 5 % of the treasury')
  }

  bot.quit()
  await sleep(1500)
  const token = `Hello-${Date.now() % 100000}`
  const queued = await rcon(`smpeco notify ${NAME} ${token}`)
  assert(/queued/.test(queued), `notify an offline player (${queued.trim()})`)
  const bot2 = await connectBot(NAME)
  const got = await waitForMessage(bot2, new RegExp(token), 10000, { includePast: true }).then(() => true, () => false)
  assert(got, 'pending message delivered on join')
  await sleep(2500)
  assertEq(bot2.messages.filter((msg) => msg.includes(token)).length, 1, 'message delivered exactly once')

  await runGuiTests(bot2)
  await runPlacementTests(bot2)
})

async function runGuiTests(bot) {
  await rcon(`op ${NAME}`)
  await rcon(`clear ${NAME}`)
  await sleep(500)
  try {
    let win = await expectWindow(bot, () => bot.chat('/smpeco guitest'))
    assert(/GUI Test/.test(windowTitle(win)), `GUI opened (title "${windowTitle(win)}")`)
    assertEq(itemLabel(win.slots[0]), 'Entry 1', 'page 1 starts with entry 1')
    assertEq(itemLabel(win.slots[49]), 'Page 1/2', 'page indicator 1/2')
    assert(itemLore(win.slots[0]).some((l) => /Click/.test(l)), 'lore shown')
    assertEq(win.slots[48]?.name, 'gray_stained_glass_pane', 'no "back" on page 1')

    await clickSlot(bot, 50)
    await sleep(300)
    win = bot.currentWindow
    assertEq(itemLabel(win.slots[49]), 'Page 2/2', 'page indicator 2/2')
    assertEq(itemLabel(win.slots[0]), 'Entry 46', 'page 2 starts with entry 46')
    assert(!win.slots[15], 'empty slots on the last page')

    let dialog = await expectWindow(bot, () => clickSlot(bot, 2))
    assert(/Confirm/.test(windowTitle(dialog)), 'confirm dialog opened')
    assertEq([itemLabel(dialog.slots[9]), itemLabel(dialog.slots[15])], ['Confirm', 'Cancel'], 'Buttons Confirm/Cancel')
    assertEq(itemLabel(dialog.slots[13]), 'Entry 48', 'preview shows the entry')
    win = await expectWindow(bot, () => clickSlot(bot, 15))
    assertEq(itemLabel(win.slots[49]), 'Page 2/2', 'cancel returns to the same page')

    dialog = await expectWindow(bot, () => clickSlot(bot, 0))
    const confirmed = waitForMessage(bot, /SMP » GUI test confirmed: Entry 46/, 5000).then(() => true, () => false)
    await clickSlot(bot, 9)
    assert(await confirmed, 'confirm runs the action')
    await sleep(300)
    assert(!bot.currentWindow, 'dialog closed after confirm')

    win = await expectWindow(bot, () => bot.chat('/smpeco guitest'))
    const framed = await expectWindow(bot, () => clickSlot(bot, 45))
    assert(/Frame/.test(windowTitle(framed)), 'frame GUI opened')
    assertEq(framed.slots[0]?.name, 'black_stained_glass_pane', 'frame top left')
    assertEq(framed.slots[17]?.name, 'black_stained_glass_pane', 'frame right')
    assertEq(itemLabel(framed.slots[10]), 'Field 1', 'first inner slot')
    const right = waitForMessage(bot, /GUI test field 1 right/, 5000).then(() => true, () => false)
    await clickSlot(bot, 10, 1, 0)
    assert(await right, 'right click detected')
    const shift = waitForMessage(bot, /GUI test field 2 left shift/, 5000).then(() => true, () => false)
    await clickSlot(bot, 11, 0, 1)
    assert(await shift, 'shift click detected')
    // try to pull a frame item into the inventory with a number key
    await clickSlot(bot, 1, 0, 2)
    await sleep(300)
    assertEq(itemLabel(framed.slots[27]), '« Back', 'back button is called "« Back"')
    const back = await expectWindow(bot, () => clickSlot(bot, 27))
    assert(/GUI Test/.test(windowTitle(back)) && !/Frame/.test(windowTitle(back)), 'back button opens the list')

    let anvil = await expectWindow(bot, () => clickSlot(bot, 46))
    assert(/Input/.test(windowTitle(anvil)), `anvil input opened (${anvil.type})`)
    assertEq(itemLabel(anvil.slots[0]), 'Search', 'default text in anvil')
    bot._client.write('name_item', { name: 'Diamond ' })
    await sleep(400)
    const typed = waitForMessage(bot, /GUI test input: \[Diamond\]/, 5000).then(() => true, () => false)
    await clickSlot(bot, 2)
    assert(await typed, 'input taken (trimmed)')
    await sleep(300)
    win = await expectWindow(bot, () => bot.chat('/smpeco guitest'))
    anvil = await expectWindow(bot, () => clickSlot(bot, 46))
    const list = await expectWindow(bot, () => clickSlot(bot, 1))
    assert(/GUI Test/.test(windowTitle(list)) && !/Input/.test(windowTitle(list)), 'cancel opens the list again')
    bot.closeWindow(list)
    await sleep(500)
    const leaked = bot.inventory.items().map((it) => it.name)
    assertEq(leaked, [], 'no GUI items in the inventory')
  } finally {
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    await rcon(`deop ${NAME}`)
    await rcon(`clear ${NAME}`)
  }
}

async function runPlacementTests(bot) {
  await rcon(`forceload add ${X} ${Z}`)
  await sleep(1500)
  await rcon(`fill ${X - 3} ${Y - 1} ${Z - 2} ${X + 2} ${Y + 3} ${Z + 3} minecraft:air`)
  await rcon(`fill ${X - 2} ${Y - 1} ${Z - 2} ${X + 2} ${Y - 1} ${Z + 3} minecraft:glass`)
  await rcon(`gamemode creative ${NAME}`)
  await rcon(`tp ${NAME} ${X + 0.5} ${Y} ${Z + 2.5}`)
  await sleep(1500)
  await rcon(`clear ${NAME}`)
  await rcon(`give ${NAME} minecraft:stone 2`)
  await rcon(`give ${NAME} minecraft:sand 1`)
  await sleep(500)

  try {
    assertEq(await placed(X + 1, Y - 1, Z), false, 'glass set with /fill is not "placed"')

    await equip(bot, 'stone')
    await placeOnTop(bot, X + 1, Y - 1, Z)
    assertEq(bot.blockAt(new Vec3(X + 1, Y, Z))?.name, 'stone', 'stone placed')
    assertEq(await placed(X + 1, Y, Z), true, 'placed stone is marked')

    await equip(bot, 'sand')
    await placeOnTop(bot, X + 1, Y, Z)
    assertEq(await placed(X + 1, Y + 1, Z), true, 'sand on the stone marked')
    await bot.dig(bot.blockAt(new Vec3(X + 1, Y, Z)))
    await sleep(1500)
    assertEq(bot.blockAt(new Vec3(X + 1, Y, Z))?.name, 'sand', 'sand has fallen')
    assertEq(await placed(X + 1, Y, Z), true, 'landed sand stays marked')
    assertEq(await placed(X + 1, Y + 1, Z), false, 'old sand position free')

    // breaking clears the mark at the end of the tick
    await bot.dig(bot.blockAt(new Vec3(X + 1, Y, Z)))
    await sleep(300)
    assertEq(await placed(X + 1, Y, Z), false, 'broken block no longer marked')

    await rcon(`setblock ${X - 2} ${Y} ${Z} minecraft:piston[facing=east]`)
    await equip(bot, 'stone')
    await placeOnTop(bot, X - 1, Y - 1, Z)
    assertEq(await placed(X - 1, Y, Z), true, 'stone in front of the piston marked')
    await rcon(`setblock ${X - 3} ${Y} ${Z} minecraft:redstone_block`)
    await sleep(1000)
    assertEq(bot.blockAt(new Vec3(X, Y, Z))?.name, 'stone', 'piston moved the stone')
    assertEq(await placed(X, Y, Z), true, 'mark moved along')
    assertEq(await placed(X - 1, Y, Z), false, 'old position (piston head) not marked')
  } finally {
    await rcon(`fill ${X - 3} ${Y - 1} ${Z - 2} ${X + 2} ${Y + 3} ${Z + 3} minecraft:air`)
    await sleep(300)
    assertEq(await placed(X, Y, Z), false, 'cleanup removes marks')
    await rcon(`clear ${NAME}`)
    await rcon(`gamemode survival ${NAME}`)
    await rcon(`forceload remove ${X} ${Z}`)
  }
}
