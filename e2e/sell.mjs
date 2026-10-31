// Sell: /worth, /sell hand, /sell all (confirmation; armor and offhand stay), price modifier, sell GUI with
// real slots (shift click, number key, offhand swap, double-click collect, drag, cancel) and selling on close
// by every path: ESC, another GUI opening, dimension change, disconnect, death. Unsellable items land in the
// new dimension's inventory, and before the death drops. Also the return store and creative mode.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, expectWindow, clickSlot, shiftClick,
  itemLabel, itemLore, windowTitle, closeWindow, hotbar,
} from './lib.mjs'

const B1 = 'sellBot1'
const B2 = 'sellBot2'
const WHEAT = 'minecraft:wheat'
const COBBLE = 'minecraft:cobblestone'
const DIAMOND = 'minecraft:diamond'
const NAMED_DIAMOND = 'minecraft:diamond[custom_name="TestItem"]'
const SOLD_RE = (n) => new RegExp(`Sold ${n} items for \\$([\\d,]+\\.\\d\\d)`)

const toCents = (s) => Math.round(Number(String(s).replace(/[$,]/g, '')) * 100)
const msg = (bot, re, ms = 6000) => waitForMessage(bot, re, ms).then((m) => m, () => null)

async function waitUntil(cond, timeoutMs = 5000, stepMs = 100) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    if (await cond()) return true
    await sleep(stepMs)
  }
  return !!(await cond())
}

async function balance(name) {
  const out = await rcon(`smpeco sell balance ${name}`)
  const m = out.match(/balance=(\d+)/)
  if (!m) throw new Error(`smpeco sell balance ${name}: ${out}`)
  return Number(m[1])
}

async function openSessions() {
  const out = await rcon(`smpeco sell balance ${B1}`)
  const m = out.match(/open=(\d+)/)
  return m ? Number(m[1]) : -1
}

async function price(item, name) {
  const out = await rcon(`smpeco price ${item} ${name}`)
  const m = out.match(/sell=(-?\d+)/)
  const base = out.match(/baseSell=(-?\d+)/)
  if (!m) throw new Error(`smpeco price ${item}: ${out}`)
  return { sell: Number(m[1]), base: base ? Number(base[1]) : -1, raw: out }
}

function invCount(bot, name, { offhand = false } = {}) {
  let n = 0
  const last = offhand ? 45 : 44
  for (let i = 9; i <= last; i++) {
    const it = bot.inventory.slots[i]
    if (it && it.name === name) n += it.count
  }
  return n
}

function winCount(win, name, from, to) {
  let n = 0
  for (let i = from; i < to; i++) {
    const it = win.slots[i]
    if (it && it.name === name) n += it.count
  }
  return n
}

/** A raw packet with an impossible stateId makes the server answer with the full window content. */
async function rawClick(bot, slot, mouseButton, mode) {
  const win = bot.currentWindow
  if (!win) throw new Error(`rawClick(${slot}) without an open window`)
  const synced = new Promise((resolve) => {
    const timer = setTimeout(resolve, 2000)
    bot.once(`setWindowItems:${win.id}`, () => { clearTimeout(timer); resolve() })
  })
  bot._client.write('window_click', { windowId: win.id, stateId: -1, slot, mouseButton, mode, changedSlots: [], cursorItem: undefined })
  await synced
  await sleep(200)
}

// mineflayer simulates clicks locally, and not all kinds correctly
async function sync(bot) {
  if (!bot.currentWindow) return
  await Promise.race([bot._syncWindow(bot.currentWindow), sleep(2000)])
  await sleep(100)
}

/**
 * Vanilla kicks non-ops for spam (+20 per message or command, -1 per tick, limit 200), so more than about one
 * command per second over a longer time means a kick.
 */
async function cmd(bot, text) {
  const wait = (bot._sellLastCmd ?? 0) + 1100 - Date.now()
  if (wait > 0) await sleep(wait)
  bot._sellLastCmd = Date.now()
  bot.chat(text)
}

async function reset(bot, name) {
  await closeWindow(bot)
  await rcon(`gamemode survival ${name}`)
  await rcon(`clear ${name}`)
  await rcon(`effect give ${name} minecraft:resistance 600 4 true`)
  await rcon(`effect give ${name} minecraft:saturation 600 0 true`)
  await waitUntil(() => bot.inventory.items().length === 0 && !bot.inventory.slots[45] && !bot.inventory.slots[5], 3000)
  bot.setQuickBarSlot(0)
  await sleep(200)
}

async function give(bot, name, item, count, check) {
  await rcon(`give ${name} ${item} ${count}`)
  if (check) await waitUntil(check, 3000)
}

async function openSell(bot) {
  const win = await expectWindow(bot, () => cmd(bot, '/sell'), 8000)
  await sleep(300)
  return win
}

async function testWorth(bot) {
  await reset(bot, B1)
  let m = msg(bot, /not holding anything/)
  await cmd(bot, '/worth')
  assert(await m, '/worth with an empty hand: error')

  await give(bot, B1, DIAMOND, 5, () => invCount(bot, 'diamond') === 5)
  const p = await price(DIAMOND, B1)
  assert(p.sell > 0 && p.base > 0, `diamond has a price (${p.raw.trim().slice(0, 120)})`)
  const basis = msg(bot, /Base price: \$([\d,]+\.\d\d) each/)
  const eff = msg(bot, /Effective: \$([\d,]+\.\d\d) each · Stack \(5\): \$([\d,]+\.\d\d)/)
  const mod = msg(bot, /Modifier: (none|[+-]\d+%)/)
  await cmd(bot, '/worth hand')
  const b = await basis
  const e = await eff
  assert(!!b && toCents(b[1]) === p.base, `/worth: base price = ${p.base} cents`)
  assert(!!e && toCents(e[1]) === p.sell && toCents(e[2]) === 5 * p.sell, `/worth: effective ${p.sell} cents, stack ${5 * p.sell}`)
  assert(!!(await mod), '/worth: current modifier shown')

  m = msg(bot, /Stack \(64\)/)
  await cmd(bot, '/worth minecraft:wheat')
  assert(await m, '/worth <item> shows the value of a full stack')
  m = msg(bot, /Can't be sold \(no price\)/)
  await cmd(bot, '/worth minecraft:bedrock')
  assert(await m, '/worth: item without a price is not sellable')

  await give(bot, B1, NAMED_DIAMOND, 1, () => invCount(bot, 'diamond') === 6)
  bot.setQuickBarSlot(1)
  await sleep(200)
  m = msg(bot, /can't be sold: renamed/)
  await cmd(bot, '/worth')
  assert(await m, '/worth: renamed stack marked unsellable')
  bot.setQuickBarSlot(0)
}

async function testSellHand(bot) {
  const p = (await price(DIAMOND, B1)).sell
  const bal0 = await balance(B1)
  bot.setQuickBarSlot(1)
  await sleep(200)
  let m = msg(bot, /renamed/)
  await cmd(bot, '/sell hand')
  assert(await m, '/sell hand refuses renamed diamond')
  await sleep(300)
  assertEq(invCount(bot, 'diamond'), 6, 'renamed diamond stays')
  assertEq(await balance(B1), bal0, 'no money for refused items')

  bot.setQuickBarSlot(0)
  await sleep(200)
  m = msg(bot, SOLD_RE(5))
  await cmd(bot, '/sell hand')
  const sold = await m
  assert(!!sold && toCents(sold[1]) === 5 * p, `/sell hand: 5 diamonds for ${5 * p} cents`)
  assertEq(await balance(B1), bal0 + 5 * p, '/sell hand: balance')
  assert(await waitUntil(() => invCount(bot, 'diamond') === 1), '/sell hand: diamonds gone')
  assertEq(bot.inventory.slots[37]?.name === 'diamond' ? bot.inventory.slots[37].count : 0, 1, '/sell hand: other slot (renamed diamond) untouched')

  bot.setQuickBarSlot(4)
  await sleep(200)
  m = msg(bot, /not holding anything/)
  await cmd(bot, '/sell hand')
  assert(await m, '/sell hand with an empty hand: error')
  bot.setQuickBarSlot(0)
}

async function testMultiplier(bot) {
  await reset(bot, B1)
  await give(bot, B1, WHEAT, 10, () => invCount(bot, 'wheat') === 10)
  await rcon('smpeco pricemod clear')
  const before = (await price(WHEAT, B1)).sell
  await rcon(`smpeco pricemod ${WHEAT} 2 5`)
  try {
    const after = (await price(WHEAT, B1)).sell
    assert(after >= 2 * before && after <= 2 * before + 1, `modifier x2 affects the sell price (${before} to ${after})`)
    const bal0 = await balance(B1)
    const m = msg(bot, /Sold 10 items for \$([\d,]+\.\d\d).*\+100% Admin/)
    await cmd(bot, '/sell hand')
    const sold = await m
    assert(!!sold, 'summary names the active bonus (+100% Admin)')
    assert(!!sold && toCents(sold[1]) === 10 * after, `proceeds with modifier (${10 * after} cents)`)
    assertEq(await balance(B1), bal0 + 10 * after, 'balance with modifier')
  } finally {
    await rcon('smpeco pricemod clear')
  }
}

async function testSellAll(bot1, bot2) {
  await reset(bot1, B1)
  await reset(bot2, B2)
  await give(bot1, B1, WHEAT, 64)
  await give(bot1, B1, COBBLE, 32)
  await give(bot1, B1, NAMED_DIAMOND, 1)
  await rcon(`item replace entity ${B1} armor.head with minecraft:wither_skeleton_skull`)
  await rcon(`item replace entity ${B1} weapon.offhand with ${WHEAT} 10`)
  const ready = await waitUntil(() => invCount(bot1, 'wheat') === 64 && invCount(bot1, 'cobblestone') === 32
    && invCount(bot1, 'diamond') === 1 && bot1.inventory.slots[5]?.name === 'wither_skeleton_skull' && bot1.inventory.slots[45]?.count === 10)
  assert(ready, '/sell all: inventory prepared (head and offhand filled)')
  const pW = (await price(WHEAT, B1)).sell
  const pC = (await price(COBBLE, B1)).sell
  const expected = 64 * pW + 32 * pC
  const bal0 = await balance(B1)

  let m = msg(bot1, /Sell all sellable items \(([\d,]+)\) for \$([\d,]+\.\d\d)\?/)
  await cmd(bot1, '/sell all')
  const preview = await m
  assert(!!preview && preview[1] === '96' && toCents(preview[2]) === expected, `/sell all: preview 96 items for ${expected} cents`)
  await sleep(300)
  assertEq(await balance(B1), bal0, '/sell all: preview pays nothing')
  assertEq(invCount(bot1, 'wheat'), 64, '/sell all: preview takes nothing')

  m = msg(bot2, /nothing sellable/)
  await cmd(bot2, '/sell all')
  assert(await m, '/sell all of another player confirms nothing (empty inventory: error)')

  m = msg(bot1, SOLD_RE(96))
  await cmd(bot1, '/sell all')
  const sold = await m
  assert(!!sold && toCents(sold[1]) === expected, '/sell all: repeat sells 96 items')
  assertEq(await balance(B1), bal0 + expected, '/sell all: balance')
  await waitUntil(() => invCount(bot1, 'wheat') === 0 && invCount(bot1, 'cobblestone') === 0)
  assertEq(invCount(bot1, 'wheat'), 0, '/sell all: wheat from the main inventory sold')
  assertEq(invCount(bot1, 'cobblestone'), 0, '/sell all: cobblestone sold')
  assertEq(invCount(bot1, 'diamond'), 1, '/sell all: renamed diamond stays')
  assertEq(bot1.inventory.slots[5]?.name, 'wither_skeleton_skull', '/sell all: armor slot (head) untouched')
  assertEq(bot1.inventory.slots[45]?.count ?? 0, 10, '/sell all: offhand untouched')

  m = msg(bot1, /nothing sellable/)
  await cmd(bot1, '/sell all')
  assert(await m, '/sell all afterwards: nothing sellable')

  // inventory changes between preview and confirmation: a new preview instead of selling something unseen
  await give(bot1, B1, COBBLE, 16, () => invCount(bot1, 'cobblestone') === 16)
  m = msg(bot1, /Sell all sellable items \(16\) for/)
  await cmd(bot1, '/sell all')
  assert(await m, '/sell all: preview 16 items')
  // sugar cane instead of wheat: /give would put wheat on the wheat stack in the offhand, which is not part of
  // /sell all
  await give(bot1, B1, 'minecraft:sugar_cane', 8, () => invCount(bot1, 'sugar_cane') === 8)
  const bal1 = await balance(B1)
  const changed = msg(bot1, /prices changed/)
  const preview2 = msg(bot1, /Sell all sellable items \(24\) for/)
  await cmd(bot1, '/sell all')
  assert(!!(await changed) && !!(await preview2), '/sell all: changed inventory gives a hint and a new preview (24 items)')
  await sleep(300)
  assertEq(await balance(B1), bal1, '/sell all: changed inventory sells nothing')
  assertEq([invCount(bot1, 'cobblestone'), invCount(bot1, 'sugar_cane')], [16, 8], '/sell all: items stay until the new confirmation')
  m = msg(bot1, SOLD_RE(24))
  await cmd(bot1, '/sell all')
  assert(!!(await m), '/sell all: confirmed new preview sells 24 items')
}

async function testGuiBasic(bot) {
  await reset(bot, B1)
  await give(bot, B1, WHEAT, 64)
  await give(bot, B1, COBBLE, 64)
  await give(bot, B1, NAMED_DIAMOND, 1)
  await waitUntil(() => invCount(bot, 'wheat') === 64 && invCount(bot, 'cobblestone') === 64 && invCount(bot, 'diamond') === 1)
  const pW = (await price(WHEAT, B1)).sell
  const pC = (await price(COBBLE, B1)).sell
  const bal0 = await balance(B1)

  const win = await openSell(bot)
  assert(/Sell/.test(windowTitle(win)), `GUI opened (title "${windowTitle(win)}")`)
  assertEq(win.slots.length >= 90, true, '6 row window with player inventory')
  assert(/Sell value: \$0\.00/.test(itemLabel(win.slots[49])), `preview empty (${itemLabel(win.slots[49])})`)
  assert(/Sell/.test(itemLabel(win.slots[53])), 'sell button')
  assert(/Cancel/.test(itemLabel(win.slots[45])), 'cancel button')
  assertEq(winCount(win, 'wheat', 0, 45), 0, 'sell slots empty')

  await shiftClick(bot, hotbar(win, 0))
  await clickSlot(bot, hotbar(win, 1))
  await clickSlot(bot, 10)
  await shiftClick(bot, hotbar(win, 2))
  await sync(bot)
  assertEq(win.slots[0]?.name === 'wheat' ? win.slots[0].count : 0, 64, 'shift click puts wheat in slot 0')
  assertEq(win.slots[10]?.name === 'cobblestone' ? win.slots[10].count : 0, 64, 'pick up and put down in slot 10')
  assertEq(winCount(win, 'diamond', 0, 45), 1, 'renamed diamond lies in the GUI')
  assertEq(winCount(win, 'wheat', win.inventoryStart, win.inventoryEnd) + winCount(win, 'cobblestone', win.inventoryStart, win.inventoryEnd), 0,
    'player inventory empty')
  await sleep(600)
  await sync(bot)
  const preview = itemLabel(win.slots[49]).match(/Sell value: \$([\d,]+\.\d\d)/)
  assert(!!preview && toCents(preview[1]) === 64 * pW + 64 * pC, `live preview shows ${64 * pW + 64 * pC} cents (${itemLabel(win.slots[49])})`)
  assert(itemLore(win.slots[49]).some((l) => /Not sellable: 1 item/.test(l)), 'preview counts unsellable items')

  const sold = msg(bot, SOLD_RE(128))
  const returned = msg(bot, /1 item couldn't be sold and was returned/)
  bot.closeWindow(win)
  const s = await sold
  assert(!!s && toCents(s[1]) === 64 * pW + 64 * pC, 'closing (ESC) sells 128 items')
  assert(await returned, 'message: unsellable items returned')
  assertEq(await balance(B1), bal0 + 64 * pW + 64 * pC, 'GUI: balance')
  await waitUntil(() => invCount(bot, 'diamond') === 1)
  assertEq(invCount(bot, 'diamond'), 1, 'renamed diamond back in the inventory')
  assertEq(invCount(bot, 'wheat') + invCount(bot, 'cobblestone'), 0, 'sold items no longer in the inventory')
}

async function testGuiClickMix(bot) {
  await reset(bot, B1)
  await give(bot, B1, WHEAT, 64)
  await give(bot, B1, WHEAT, 64)
  await rcon(`item replace entity ${B1} weapon.offhand with ${WHEAT} 10`)
  const ready = await waitUntil(() => invCount(bot, 'wheat', { offhand: true }) === 138)
  assert(ready, `click mix: 138 wheat prepared (${invCount(bot, 'wheat', { offhand: true })})`)
  const pW = (await price(WHEAT, B1)).sell
  const bal0 = await balance(B1)
  const win = await openSell(bot)

  await shiftClick(bot, hotbar(win, 0))
  await clickSlot(bot, 5, 1, 2)
  await rawClick(bot, 6, 40, 2)
  await sync(bot)
  assertEq([win.slots[0]?.count ?? 0, win.slots[5]?.count ?? 0, win.slots[6]?.count ?? 0], [64, 64, 10], 'shift click, number key, offhand swap')

  await clickSlot(bot, 6)
  await rawClick(bot, 6, 0, 6)
  await clickSlot(bot, 7)
  await sync(bot)
  assertEq(winCount(win, 'wheat', 0, 45), 138, 'double-click collect: nothing lost or duplicated')
  assertEq(win.slots[7]?.count ?? 0, 64, 'double click collected a full stack')

  await clickSlot(bot, 5)
  await rawClick(bot, -999, 0, 5)
  await rawClick(bot, 20, 1, 5)
  await rawClick(bot, 21, 1, 5)
  await rawClick(bot, 49, 1, 5) // preview item is virtual and must accept nothing
  await rawClick(bot, 22, 1, 5)
  await rawClick(bot, -999, 2, 5)
  await clickSlot(bot, 23)
  await sync(bot)
  assertEq([20, 21, 22].map((i) => win.slots[i]?.count ?? 0), [21, 21, 21], 'drag spreads 64 over 3 slots')
  assert(win.slots[49]?.name !== 'wheat', 'drag over the preview places nothing there')
  assertEq(winCount(win, 'wheat', 0, 45), 138, 'after the drag: still 138 in the GUI')

  await shiftClick(bot, 0)
  await sync(bot)
  const inGui = winCount(win, 'wheat', 0, 45)
  const inInv = winCount(win, 'wheat', win.inventoryStart, win.inventoryEnd)
  assertEq([inGui, inInv], [128, 10], 'shift click back to the inventory')

  const sold = msg(bot, SOLD_RE(128))
  bot.closeWindow(win)
  const s = await sold
  assert(!!s && toCents(s[1]) === 128 * pW, 'click mix: 128 sold')
  assertEq(await balance(B1), bal0 + 128 * pW, 'click mix: balance')
  await waitUntil(() => invCount(bot, 'wheat', { offhand: true }) === 10)
  assertEq(invCount(bot, 'wheat', { offhand: true }), 10, 'click mix: 10 in the inventory, total of 138 kept')
}

async function testGuiCancel(bot) {
  await reset(bot, B1)
  await give(bot, B1, WHEAT, 32, () => invCount(bot, 'wheat') === 32)
  const bal0 = await balance(B1)
  const win = await openSell(bot)
  await shiftClick(bot, hotbar(win, 0))
  await sync(bot)
  assertEq(winCount(win, 'wheat', 0, 45), 32, 'cancel: wheat lies in the GUI')
  const m = msg(bot, /Sale cancelled – 32 items returned/)
  await clickSlot(bot, 45)
  assert(await m, 'cancel returns everything')
  await waitUntil(() => !bot.currentWindow && invCount(bot, 'wheat') === 32)
  assert(!bot.currentWindow, 'cancel closes the menu')
  assertEq(invCount(bot, 'wheat'), 32, 'cancel: wheat back')
  assertEq(await balance(B1), bal0, 'cancel: no money')
}

async function waitingReturns(name) {
  const out = await rcon(`smpeco sell balance ${name}`)
  const m = out.match(/returns=(\d+)/)
  if (!m) throw new Error(`smpeco sell balance ${name}: ${out}`)
  return Number(m[1])
}

/**
 * Full inventory: swap the offhand into the GUI with F, refill the offhand (like picking up with the GUI open),
 * cancel. Nothing drops; the shield waits in the return store and comes back once there is room.
 */
async function testGuiFullInventoryReturn(bot) {
  await reset(bot, B1)
  for (let i = 0; i < 36; i++) await rcon(`item replace entity ${B1} container.${i} with ${COBBLE} 64`)
  await rcon(`item replace entity ${B1} weapon.offhand with minecraft:shield`)
  const ready = await waitUntil(() => invCount(bot, 'cobblestone') === 36 * 64 && bot.inventory.slots[45]?.name === 'shield')
  assert(ready, `return store: inventory full + shield in the offhand (${invCount(bot, 'cobblestone')})`)
  const bal0 = await balance(B1)
  const win = await openSell(bot)
  await rawClick(bot, 0, 40, 2)
  await sync(bot)
  assertEq(win.slots[0]?.name, 'shield', 'return store: shield in the GUI through F')
  await rcon(`item replace entity ${B1} weapon.offhand with ${NAMED_DIAMOND}`)
  await sleep(300)

  const cancelled = msg(bot, /Sale cancelled – 1 item returned/)
  const kept = msg(bot, /Inventory full: 1 item is kept for you/)
  await clickSlot(bot, 45)
  assert(await cancelled, 'return store: cancel reports the return')
  assert(await kept, 'return store: hint "is kept for you"')
  assertEq(await waitingReturns(B1), 1, 'return store: 1 item waiting')
  const ents = await rcon('execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:shield"}}]')
  assert(/failed/i.test(ents), `return store: no shield on the ground (${ents.trim()})`)
  assert(!bot.messages.slice(-10).some((l) => /at your feet/.test(l)), 'return store: nothing dropped at the feet')
  await sleep(1500)
  assertEq(await waitingReturns(B1), 1, 'return store: stays stored without room')

  const back = msg(bot, /1 item from the sell menu is now in your inventory/, 6000)
  await rcon(`clear ${B1} ${COBBLE} 64`)
  assert(await back, 'return store: returned once there is room')
  assert(await waitUntil(() => invCount(bot, 'shield') === 1, 3000), 'return store: shield back in the inventory')
  assertEq(await waitingReturns(B1), 0, 'return store: empty')
  assertEq(await balance(B1), bal0, 'return store: no money moved')
}

async function testGuiOtherGuiOpens(bot) {
  await reset(bot, B1)
  await give(bot, B1, WHEAT, 16, () => invCount(bot, 'wheat') === 16)
  const pW = (await price(WHEAT, B1)).sell
  const bal0 = await balance(B1)
  await rcon(`op ${B1}`)
  try {
    const win = await openSell(bot)
    await shiftClick(bot, hotbar(win, 0))
    await sync(bot)
    assertEq(winCount(win, 'wheat', 0, 45), 16, 'other GUI: wheat lies in the GUI')
    const sold = msg(bot, SOLD_RE(16))
    const other = await expectWindow(bot, () => cmd(bot, '/smpeco guitest'), 8000)
    assert(/GUI Test/.test(windowTitle(other)), 'other GUI opened')
    const s = await sold
    assert(!!s && toCents(s[1]) === 16 * pW, 'opening another GUI sells the content')
    assertEq(await balance(B1), bal0 + 16 * pW, 'other GUI: balance')
    await closeWindow(bot)
    assertEq(invCount(bot, 'wheat'), 0, 'other GUI: nothing back in the inventory (sold)')
  } finally {
    await closeWindow(bot)
    await rcon(`deop ${B1}`)
  }
}

async function testGuiDimensionChange(bot) {
  await reset(bot, B1)
  await give(bot, B1, WHEAT, 12)
  await give(bot, B1, NAMED_DIAMOND, 1)
  await waitUntil(() => invCount(bot, 'wheat') === 12 && invCount(bot, 'diamond') === 1)
  const pW = (await price(WHEAT, B1)).sell
  const bal0 = await balance(B1)
  const home = bot.entity.position.clone()
  const win = await openSell(bot)
  await shiftClick(bot, hotbar(win, 0))
  await shiftClick(bot, hotbar(win, 1))
  await sync(bot)
  assertEq([winCount(win, 'wheat', 0, 45), winCount(win, 'diamond', 0, 45)], [12, 1],
    'dimension change: wheat and renamed diamond lie in the GUI')
  const since = bot.messages.length
  const sold = msg(bot, SOLD_RE(12), 10000)
  const returned = msg(bot, /1 item couldn't be sold and was returned/, 10000)
  await rcon(`execute in minecraft:the_nether run tp ${B1} 0 120 0`)
  const s = await sold
  assert(!!s && toCents(s[1]) === 12 * pW, 'dimension change with an open GUI sells the content')
  assertEq(await balance(B1), bal0 + 12 * pW, 'dimension change: balance')
  assert(await returned, 'dimension change: message about the returned diamond')
  // unsellable items must not drop in the old dimension (player still counts as "removed" on close)
  const back = await waitUntil(() => invCount(bot, 'diamond') === 1, 5000)
  assert(back, 'dimension change: renamed diamond in the inventory (not dropped in the old dimension)')
  assert(!bot.messages.slice(since).some((l) => /at your feet/.test(l)), 'dimension change: nothing dropped at the feet')
  assert(await waitUntil(async () => (await openSessions()) === 0, 3000, 250), 'dimension change: session settled')
  await rcon(`execute in minecraft:overworld run tp ${B1} ${home.x.toFixed(2)} ${home.y.toFixed(2)} ${home.z.toFixed(2)}`)
  await sleep(1500)
}

async function testCreativeBlocked(bot) {
  await reset(bot, B1)
  await rcon(`gamemode creative ${B1}`)
  try {
    await give(bot, B1, WHEAT, 5, () => invCount(bot, 'wheat') === 5)
    const bal0 = await balance(B1)
    let m = msg(bot, /Creative/)
    await cmd(bot, '/sell hand')
    assert(await m, 'creative mode: /sell hand refused')
    m = msg(bot, /Creative/)
    await cmd(bot, '/sell')
    assert(await m, 'creative mode: /sell GUI refused')
    await sleep(300)
    assert(!bot.currentWindow, 'creative mode: no window')
    assertEq(await balance(B1), bal0, 'creative mode: no money')
  } finally {
    await rcon(`gamemode survival ${B1}`)
  }
}

async function testGuiDisconnect(bot) {
  await reset(bot, B2)
  await give(bot, B2, WHEAT, 20)
  await give(bot, B2, NAMED_DIAMOND, 1)
  await waitUntil(() => invCount(bot, 'wheat') === 20 && invCount(bot, 'diamond') === 1)
  const pW = (await price(WHEAT, B2)).sell
  const bal0 = await balance(B2)
  const win = await openSell(bot)
  await shiftClick(bot, hotbar(win, 0))
  await shiftClick(bot, hotbar(win, 1))
  await sync(bot)
  assertEq([winCount(win, 'wheat', 0, 45), winCount(win, 'diamond', 0, 45)], [20, 1], 'disconnect: items lie in the GUI')

  const ended = new Promise((resolve) => bot.once('end', resolve))
  bot.quit()
  await Promise.race([ended, sleep(5000)])
  const paid = await waitUntil(async () => (await balance(B2)) === bal0 + 20 * pW, 5000, 250)
  assert(paid, 'disconnect with an open GUI sells (balance)')

  const bot2 = await connectBot(B2)
  const back = await waitUntil(() => invCount(bot2, 'diamond') === 1, 6000)
  assert(back, 'disconnect: renamed diamond in the inventory after the rejoin')
  assertEq(invCount(bot2, 'wheat'), 0, 'disconnect: sold wheat not duplicated')
  const pending = await msg(bot2, /Sold 20 items .*when you left/, 6000)
  assert(!!pending || bot2.messages.some((l) => /Sold 20 items .*when you left/.test(l)), 'disconnect: summary on the next join')
  return bot2
}

async function testGuiDeath(bot) {
  await reset(bot, B2)
  await give(bot, B2, WHEAT, 8)
  await give(bot, B2, NAMED_DIAMOND, 1)
  await waitUntil(() => invCount(bot, 'wheat') === 8 && invCount(bot, 'diamond') === 1)
  const pW = (await price(WHEAT, B2)).sell
  const bal0 = await balance(B2)
  const win = await openSell(bot)
  await shiftClick(bot, hotbar(win, 0))
  await shiftClick(bot, hotbar(win, 1))
  await sync(bot)
  assertEq([winCount(win, 'wheat', 0, 45), winCount(win, 'diamond', 0, 45)], [8, 1], 'death: wheat and renamed diamond lie in the GUI')
  await rcon(`clear ${B2}`) // own inventory empty, so on death only what the GUI returns drops
  // keep_inventory on briefly: unsellable items must reach the inventory before the death drops and then stay
  const keepOut = await rcon('gamerule keep_inventory')
  const keep = keepOut.match(/(true|false)\s*$/)
  assert(!!keep, `keep_inventory queryable (${keepOut.trim()})`)
  if (keep) await rcon('gamerule keep_inventory true')
  try {
    const sold = msg(bot, SOLD_RE(8), 8000)
    const returned = msg(bot, /1 item couldn't be sold and was returned/, 8000)
    const respawned = new Promise((resolve) => {
      const timer = setTimeout(resolve, 10000)
      bot.once('spawn', () => { clearTimeout(timer); resolve() })
    })
    await rcon(`kill ${B2}`)
    const s = await sold
    assert(!!s && toCents(s[1]) === 8 * pW, 'death with an open GUI sells the content')
    assertEq(await balance(B2), bal0 + 8 * pW, 'death: balance')
    assert(await returned, 'death: message about the returned diamond')
    if (keep) {
      const ents = await rcon('execute if entity @e[type=minecraft:item,nbt={Item:{id:"minecraft:diamond"}}]')
      assert(/failed/i.test(ents), `death: no diamond as an item on the ground (${ents.trim()})`)
    }
    await respawned
    if (keep) {
      const kept = await waitUntil(() => invCount(bot, 'diamond') === 1, 5000)
      assert(kept, 'death with keep_inventory: renamed diamond in the inventory after the respawn')
    }
  } finally {
    if (keep) await rcon(`gamerule keep_inventory ${keep[1]}`)
  }
  await sleep(500)
}

runE2E(async () => {
  const bot1 = await connectBot(B1)
  let bot2 = await connectBot(B2)
  await rcon('smpeco pricemod clear')
  try {
    await testWorth(bot1)
    await testSellHand(bot1)
    await testMultiplier(bot1)
    await testSellAll(bot1, bot2)
    await testGuiBasic(bot1)
    await testGuiClickMix(bot1)
    await testGuiCancel(bot1)
    await testGuiFullInventoryReturn(bot1)
    await testGuiOtherGuiOpens(bot1)
    await testGuiDimensionChange(bot1)
    await testCreativeBlocked(bot1)
    bot2 = await testGuiDisconnect(bot2)
    await testGuiDeath(bot2)
    await closeWindow(bot1)
    await closeWindow(bot2)
    await sleep(500)
    assertEq(await openSessions(), 0, 'no open sell sessions left')
  } finally {
    await rcon('smpeco pricemod clear')
    await rcon(`deop ${B1}`)
    for (const name of [B1, B2]) {
      await rcon(`clear ${name}`)
      await rcon(`gamemode survival ${name}`)
    }
  }
}, { timeoutMs: 300000 })
