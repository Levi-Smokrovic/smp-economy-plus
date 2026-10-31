// Server lifecycle with a real stop and a real crash, not only simulated in selftests. Runs last in run-all.mjs
// and restarts the server twice. (a) Clean stop with everything open at once: /sell GUI, open coinflip, auction
// listing, mines round, crate animation, hologram, treasure chest, market shock. Each settles exactly once and
// the state survives. (b) kill -9 after save-all flush with transactions just before the crash: items back
// exactly once, no duplicates, deliveries all or nothing.
import {
  connectBot, rcon, closeRcon, assert, assertEq, sleep, runE2E, waitForMessage, expectWindow, clickSlot,
  shiftClick, findSlot, itemLabel, itemLore, windowTitle, stripColors, closeWindow, cmd, countItem, hotbar,
  kv, walletCents,
} from './lib.mjs'
import { restartServer } from '../tools/server-restart.mjs'

const A = 'restartBot1' // /sell
const B = 'restartBot2' // coinflip + auction seller
const C = 'restartBot3' // mines (a) / supplier (b)
const D = 'restartBot4' // crates + hologram + auction buyer (a) / order owner + mines (b)
const BOTS = [A, B, C, D]
const X = -4700, Y = 230, Z = 4700 // own test area far from spawn, builds and other scripts' areas
const TX = X + 6 // treasure chest
const STAKE = 1000 // mines stake $10
const BOMBS = 3
const SHOCK_ITEM = 'minecraft:quartz' // not used by any other e2e script
const AH_PRICE = 123400 // $1,234 for 3 prismarine shards, far above any NPC buy price

async function sellInfo(name) {
  const v = kv(await rcon(`smpeco sell balance ${name}`))
  if (v.balance === undefined) throw new Error(`smpeco sell balance ${name}`)
  return v
}

async function sellPrice(item, name) {
  const out = await rcon(`smpeco price ${item} ${name}`)
  const m = out.match(/\bsell=(-?\d+)/)
  if (!m) throw new Error(`smpeco price ${item} ${name}: ${out}`)
  return Number(m[1])
}

async function casino(name) {
  const v = kv(await rcon(`smpeco casino info ${name}`))
  if (v.balance === undefined) throw new Error(`smpeco casino info ${name}`)
  return v
}

async function casinoPeek(name) {
  const v = kv(await rcon(`smpeco casino peek ${name}`))
  const list = (s) => (typeof s === 'string' && s.startsWith('[') ? s.slice(1, -1).split(',').filter(Boolean).map(Number) : [])
  v.minesList = list(v.mines)
  return v
}

async function ahInfo(name) {
  const m = (await rcon(`ah admin info ${name}`)).match(/active=(\d+) claims=(\d+) balance=(\d+)/)
  if (!m) throw new Error(`ah admin info ${name}`)
  return { active: Number(m[1]), claims: Number(m[2]), balance: Number(m[3]) }
}

async function ordersInfo(name) {
  const m = (await rcon(`orders admin info ${name}`)).match(/balance=(\d+) escrow=(\d+) orders=(\d+) claim=(\d+) ids=(\S+)/)
  if (!m) throw new Error(`orders admin info ${name}`)
  return { balance: Number(m[1]), escrow: Number(m[2]), orders: Number(m[3]), claim: Number(m[4]), ids: m[5] === '-' ? [] : m[5].split(',').map(Number) }
}

async function crates(name) {
  const m = (await rcon(`crates info ${name}`)).match(/common=(\d+) rare=(\d+) epic=(\d+) pending=(\d+) money=(\d+)/)
  if (!m) throw new Error(`crates info ${name}`)
  return { common: Number(m[1]), pending: Number(m[4]) }
}

async function newsEntry(id) {
  const out = stripColors(await rcon('news status'))
  const m = out.match(new RegExp(`news entry id=${id} type=(\\w+) item=(\\S+) .*?remaining=(\\d+)s`))
  return m ? { type: m[1], item: m[2], remaining: Number(m[3]) } : null
}

async function priceMod(item) {
  const m = (await rcon(`smpeco price ${item}`)).match(/\bmod=(-?[\d.]+)/)
  return m ? Number(m[1]) : NaN
}

async function countEntities(selector) {
  const out = await rcon(`execute if entity ${selector}`)
  const m = out.match(/count:?\s*(\d+)/i)
  if (m) return Number(m[1])
  if (/failed/i.test(out)) return 0
  throw new Error(`execute if entity ${selector}: ${out}`)
}

const holoSel = (id) => `@e[type=minecraft:text_display,tag=smpeco_holo_${id}]`
const HOLO_HERE = `@e[type=minecraft:text_display,tag=smpeco_holo,x=${X},y=${Y},z=${Z},distance=..30]`

async function chestAt(x, y, z) {
  const out = await rcon(`execute if block ${x} ${y} ${z} minecraft:chest`)
  if (/passed/i.test(out)) return true
  if (/failed/i.test(out)) return false
  throw new Error(`execute if block ${x} ${y} ${z}: ${out}`)
}

async function waitFor(fn, timeoutMs, stepMs = 500) {
  const end = Date.now() + timeoutMs
  let last
  for (;;) {
    last = await fn()
    if (last) return last
    if (Date.now() > end) return last
    await sleep(stepMs)
  }
}

/** Mines payout as on the server (house edge 4 % = 400 basis points). */
function minesPayout(stake, bombs, k, edgeBp = 400) {
  const nCr = (n, r) => {
    let x = 1n
    for (let i = 1n; i <= BigInt(r); i++) x = (x * (BigInt(n) - BigInt(r) + i)) / i
    return x
  }
  return Number((BigInt(stake) * BigInt(10000 - edgeBp) * nCr(25, k)) / (10000n * nCr(25 - bombs, k)))
}

const minesSlot = (t) => Math.floor(t / 5) * 9 + 2 + (t % 5)

function winCount(win, name, from, to) {
  let n = 0
  for (let i = from; i < to; i++) if (win.slots[i]?.name === name) n += win.slots[i].count
  return n
}

/**
 * /ah sell <total price>: stacks and dump prices show a preview with [Confirm] first and list on repeat, like
 * the chat click. The caller waits for the "Listed" message itself.
 */
async function ahSell(bot, amount) {
  const first = waitForMessage(bot, /Listed|\[Confirm\]/, 8000).then((m) => m[0], () => null)
  await cmd(bot, `/ah sell ${amount}`)
  if ((await first) === '[Confirm]') await cmd(bot, `/ah sell ${amount}`)
}

async function waitSlot(bot, slot, regex, timeoutMs = 4000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const item = bot.currentWindow?.slots?.[slot]
    if (item && regex.test(itemLabel(item))) return item
    await sleep(100)
  }
  return null
}

async function hold(bot, itemName) {
  await waitFor(() => bot.inventory.items().some((it) => it.name === itemName), 5000, 100)
  const item = bot.inventory.items().find((it) => it.name === itemName)
  if (!item) throw new Error(`${bot.username}: ${itemName} not in inventory`)
  await bot.equip(item, 'hand')
  await sleep(300)
}

async function give(name, item, n) {
  await rcon(`give ${name} ${item} ${n}`)
  const ok = await waitFor(async () => (await countItem(name, item)) >= n, 5000, 200)
  if (!ok) throw new Error(`give ${name} ${item} ${n} had no effect`)
}

const usd = (cents) => (cents / 100).toFixed(2)

// current bot objects (new after each restart), also for cleanup after an exception
let live = {}

async function connectAll() {
  const bots = {}
  for (const name of BOTS) {
    bots[name] = await connectBot(name)
    live[name] = bots[name]
    await sleep(300)
  }
  await sleep(1500)
  return bots
}

async function prepareArea() {
  await rcon(`forceload add ${X - 16} ${Z - 16} ${X + 16} ${Z + 16}`)
  await waitFor(async () => /passed/i.test(await rcon(`execute if loaded ${X} ${Y} ${Z}`)), 30000)
  await rcon(`fill ${X - 8} ${Y - 1} ${Z - 8} ${X + 8} ${Y - 1} ${Z + 8} minecraft:glass`)
  await rcon(`fill ${X - 8} ${Y} ${Z - 8} ${X + 8} ${Y + 4} ${Z + 8} minecraft:air`)
}

async function tpAll() {
  const spots = { [A]: [X + 0.5, Z + 0.5], [B]: [X + 2.5, Z + 0.5], [C]: [X - 1.5, Z + 0.5], [D]: [X + 0.5, Z + 3.5] }
  for (const [name, [x, z]] of Object.entries(spots)) await rcon(`tp ${name} ${x} ${Y} ${z}`)
  await sleep(800)
}

async function removeOldHolos() {
  const out = await rcon('holo list')
  for (const m of out.matchAll(/#(\d+) \S+ » (\S+) (-?[\d.]+), (-?[\d.]+), (-?[\d.]+)/g)) {
    const [, id, dim, x, , z] = m
    if (dim === 'overworld' && Math.abs(Number(x) - X) < 40 && Math.abs(Number(z) - Z) < 40) await rcon(`holo remove ${id}`)
  }
}

async function resetState(bots) {
  for (const name of BOTS) {
    await closeWindow(bots[name])
    await rcon(`gamemode survival ${name}`)
    await rcon(`ah admin purge ${name}`)
    for (const id of (await ordersInfo(name)).ids) await rcon(`orders admin cancel ${id}`)
    await rcon(`smpeco casino settle ${name}`)
    await rcon(`smpeco casino resetlimit ${name}`)
    await rcon(`crates set ${name} common 0`)
    await rcon(`clear ${name}`)
  }
  for (const name of BOTS) {
    const bot = bots[name]
    if ((await ordersInfo(name)).claim > 0) { await cmd(bot, '/orders claim'); await sleep(1000) }
    if ((await crates(name)).pending > 0) { await cmd(bot, '/crates claim'); await sleep(1000) }
    await cmd(bot, '/coinflip cancel')
  }
  await sleep(1000)
  for (const name of BOTS) {
    await rcon(`clear ${name}`)
    await rcon(`eco set ${name} 0`)
  }
}

async function cleanup() {
  const bots = live
  for (const name of BOTS) await closeWindow(bots[name])
  const steps = [`deop ${D}`, 'treasure stop', 'news clear']
  if (holoId) steps.push(`holo remove ${holoId}`)
  for (const name of BOTS) {
    steps.push(`ah admin purge ${name}`, `smpeco casino settle ${name}`, `smpeco casino resetlimit ${name}`, `crates set ${name} common 0`)
  }
  for (const s of steps) {
    try { await rcon(s) } catch (err) { console.log(`[E2E] INFO cleanup "${s}" failed: ${err?.message ?? err}`) }
  }
  try {
    for (const name of BOTS) for (const id of (await ordersInfo(name)).ids) await rcon(`orders admin cancel ${id}`)
    const claimer = bots[D]
    if (claimer && (await ordersInfo(D)).claim > 0) { await cmd(claimer, '/orders claim'); await sleep(1000) }
    for (const name of BOTS) {
      await rcon(`clear ${name}`)
      await rcon(`eco set ${name} 0`)
    }
    await rcon(`fill ${X - 8} ${Y - 1} ${Z - 8} ${X + 8} ${Y + 4} ${Z + 8} minecraft:air`)
    await rcon(`forceload remove ${X - 16} ${Z - 16} ${X + 16} ${Z + 16}`)
  } catch (err) {
    console.log(`[E2E] INFO cleanup failed: ${err?.message ?? err}`)
  }
}

async function restart(kill) {
  await closeRcon()
  live = {}
  await restartServer({ kill })
  return connectAll()
}

let holoId = 0

async function gracefulStop(bots) {
  const a = bots[A], b = bots[B], c = bots[C], d = bots[D]

  // hologram (admin, as in holo.mjs) at bot 4, which stays there
  await rcon(`op ${D}`)
  const created = waitForMessage(d, /Hologram #(\d+) \(Money\) created/, 8000).then((m) => Number(m[1]), () => 0)
  await cmd(d, '/holo create money')
  holoId = await created
  await rcon(`deop ${D}`)
  assert(holoId > 0, `hologram #${holoId} created`)
  assertEq(await countEntities(holoSel(holoId)), 1, 'before the stop: exactly one display')

  const placed = await rcon(`treasure place ${TX} ${Y} ${Z}`)
  assert(/Treasure hidden/.test(placed), `treasure chest placed (${placed.trim()})`)
  assert(await chestAt(TX, Y, Z), 'chest placed')

  const shock = await rcon(`news trigger ${SHOCK_ITEM} -50 10`)
  const sm = shock.match(/\[id=(\d+) type=(\w+)\]/)
  assert(!!sm, `market shock started (${shock.trim().slice(0, 120)})`)
  const shockId = sm ? Number(sm[1]) : -1
  const entry0 = await newsEntry(shockId)
  assert(!!entry0 && entry0.remaining > 500, `shock active, about 10 min left (${JSON.stringify(entry0)})`)
  const mod0 = await priceMod(SHOCK_ITEM)
  assert(mod0 < 0.6, `price factor ${SHOCK_ITEM} lowered (mod=${mod0})`)

  await rcon(`eco set ${B} 1000`)
  await give(B, 'minecraft:prismarine_shard', 3)
  await hold(b, 'prismarine_shard')
  const listed = waitForMessage(b, /Listed (\d+)x/, 15000).then((m) => Number(m[1]), () => 0)
  await ahSell(b, usd(AH_PRICE))
  assertEq(await listed, 3, 'bot 2: 3 prismarine shards listed in the auction house')
  assertEq((await ahInfo(B)).active, 1, 'bot2: 1 active offer')
  const flipped = waitForMessage(b, /Coinflip for \$100\.00 created/, 6000).then(() => true, () => false)
  await cmd(b, '/coinflip 100')
  assert(await flipped, 'bot 2: open coinflip of $100')
  assertEq(await walletCents(B), 90000, 'bot 2: stake reserved')

  await rcon(`eco set ${C} 1000`)
  const c0 = await casino(C)
  const mines = await expectWindow(c, () => cmd(c, `/mines ${usd(STAKE)} ${BOMBS}`))
  assert(/Mines/.test(windowTitle(mines)), `bot 3: mines open (${windowTitle(mines)})`)
  const p = await casinoPeek(C)
  const safe = [...Array(25).keys()].find((t) => !p.minesList.includes(t))
  await clickSlot(c, minesSlot(safe))
  assert(!!(await waitSlot(c, minesSlot(safe), /Safe/, 3000)), 'bot 3: a field revealed safely')
  assertEq((await casino(C)).open, 'mines:playing', 'bot 3: round running')

  await give(A, 'minecraft:wheat', 64)
  await waitFor(() => a.inventory.items().some((it) => it.name === 'wheat' && it.count === 64), 5000, 100)
  const price = await sellPrice('minecraft:wheat', A)
  assert(price > 0, `wheat price for bot 1: ${price} cents`)
  const s0 = await sellInfo(A)
  const sellWin = await expectWindow(a, () => cmd(a, '/sell'), 8000)
  await sleep(300)
  await shiftClick(a, hotbar(sellWin, 0))
  await sleep(500)
  assertEq(winCount(a.currentWindow ?? sellWin, 'wheat', 0, 45), 64, 'bot 1: 64 wheat lie in the sell GUI')
  assertEq(await countItem(A, 'minecraft:wheat'), 0, 'bot 1: wheat no longer in the inventory')

  await rcon(`crates give ${D} common 1`)
  await rcon(`crates force ${D} common diamonds`)
  const dia0 = await countItem(D, 'minecraft:diamond')
  const menu = await expectWindow(d, () => cmd(d, '/crates'))
  const crateSlot = findSlot(menu, /Common Crate/)
  assert(crateSlot >= 0, 'bot 4: Common Crate in the menu')
  const spin = await expectWindow(d, () => clickSlot(d, crateSlot))
  assert(/opening/.test(windowTitle(spin)), `bot 4: spin animation running (${windowTitle(spin)})`)
  const k0 = await crates(D)
  assertEq([k0.common, k0.pending], [0, 1], 'bot 4: key used, prize stored, not delivered yet')
  assertEq((await sellInfo(A)).open, 1, 'bot 1: sell GUI still open')

  bots = await restart(false)
  assert(true, 'server restarted after "stop"')
  await sleep(2500) // join deliveries (crates 2 s, pending messages 1 s, journal 1 tick)

  const s1 = await sellInfo(A)
  assertEq(s1.balance - s0.balance, 64 * price, `stop: bot 1 got exactly 64 x ${price} cents`)
  assertEq([s1.earned - s0.earned, s1.items - s0.items], [64 * price, 64], 'stop: sell statistics +64 items, once')
  assertEq(await countItem(A, 'minecraft:wheat'), 0, 'stop: bot 1 has no wheat (not returned on top)')
  assert(!bots[A].messages.some((l) => /sell menu was interrupted by a restart/.test(stripColors(l))), 'stop: no journal entry left')

  assertEq(await walletCents(B), 100000, 'stop: coinflip refunded exactly once ($1,000)')
  assert(!new RegExp(`creator=${B} `).test(await rcon('minigames status')), 'stop: no open coinflip left')
  assertEq((await ahInfo(B)).active, 1, 'stop: auction listing survives the restart')

  const c1 = await casino(C)
  assertEq(c1.balance, c0.balance - STAKE + minesPayout(STAKE, BOMBS, 1), 'stop: mines paid exactly once (1 field)')
  assertEq([c1.open, c1.pending], ['none', 0], 'stop: no open round, nothing pending')

  assertEq(await countItem(D, 'minecraft:diamond'), dia0 + 2, 'stop: crate prize (2 diamonds) exactly once')
  const k1 = await crates(D)
  assertEq([k1.common, k1.pending], [0, 0], 'stop: nothing open any more')

  await rcon(`eco set ${D} 2000`)
  const ahWin = await expectWindow(bots[D], () => cmd(bots[D], '/ah search prismarine'))
  assert(/Auction House/.test(windowTitle(ahWin)), `bot 4: auction search open (${windowTitle(ahWin)})`)
  const lslot = findSlot(ahWin, (it) => it.name === 'prismarine_shard' && itemLore(it).some((l) => l.includes(`Seller: ${B}`)), { to: 45 })
  assert(lslot >= 0, "stop: bot2's offer visible in the AH")
  if (lslot >= 0) {
    const dialog = await expectWindow(bots[D], () => clickSlot(bots[D], lslot))
    assert(/Confirm Purchase/.test(windowTitle(dialog)), 'bot 4: buy dialog')
    const bought = waitForMessage(bots[D], /Bought (\d+)x/, 8000).then((m) => Number(m[1]), () => 0)
    await clickSlot(bots[D], lslot === 9 ? 10 : 9)
    assertEq(await bought, 3, 'stop: listing buyable after the restart')
    assertEq(await countItem(D, 'minecraft:prismarine_shard'), 3, 'bot 4 has the 3 shards')
    assertEq((await ahInfo(B)).active, 0, 'listing gone afterwards')
    assertEq(await walletCents(B), 100000 + AH_PRICE - Math.floor(AH_PRICE * 3 / 100), 'bot 2: proceeds minus 3 % tax')
  }
  await closeWindow(bots[D])

  const entry1 = await newsEntry(shockId)
  assert(!!entry1 && entry1.remaining > 0 && entry1.remaining < entry0.remaining,
    `stop: shock active with less time left (${entry0?.remaining}s to ${entry1?.remaining}s)`)
  const mod1 = await priceMod(SHOCK_ITEM)
  assert(Math.abs(mod1 - mod0) < 0.01, `stop: price factor unchanged (mod ${mod0} to ${mod1})`)

  await rcon(`tp ${D} ${X + 0.5} ${Y} ${Z + 3.5}`)
  await waitFor(async () => /passed/i.test(await rcon(`execute if loaded ${X} ${Y} ${Z}`)), 30000)
  const holoBack = await waitFor(async () => (await countEntities(holoSel(holoId))) >= 1, 15000)
  assert(!!holoBack, 'stop: hologram display set again')
  await sleep(2500) // several guard passes (once per second)
  assertEq(await countEntities(holoSel(holoId)), 1, 'stop: exactly one display for the hologram')
  assertEq(await countEntities(HOLO_HERE), 1, 'stop: no further displays in the test area')
  assert(!(await chestAt(TX, Y, Z)), 'stop: treasure chest removed')
  assert(!new RegExp(`active=true x=${TX} y=${Y} z=${Z}`).test(await rcon('treasure info')), 'stop: treasure hunt ended')
  return bots
}

async function crash(bots) {
  for (const name of BOTS) await closeWindow(bots[name])
  await tpAll()
  for (const name of BOTS) {
    await rcon(`clear ${name}`)
    await rcon(`smpeco casino settle ${name}`)
    await rcon(`smpeco casino resetlimit ${name}`)
  }
  await rcon(`eco set ${A} 0`)
  await rcon(`eco set ${B} 1000`)
  await rcon(`eco set ${C} 0`)
  await rcon(`eco set ${D} 1000`)
  const a = bots[A], b = bots[B], c = bots[C], d = bots[D]

  // preparation, reaches disk with "save-all flush"
  await give(A, 'minecraft:wheat', 64)
  await give(B, 'minecraft:amethyst_shard', 16)
  await hold(b, 'amethyst_shard')
  await give(C, 'minecraft:diamond', 4)
  const dialog = await expectWindow(d, () => cmd(d, '/orders create minecraft:diamond 10 5'))
  assert(/Create Order/.test(windowTitle(dialog)), `bot 4: order confirmation (${windowTitle(dialog)})`)
  const createdOrder = waitForMessage(d, /Order #(\d+) created/, 8000).then((m) => Number(m[1]), () => 0)
  await clickSlot(d, 9)
  const orderId = await createdOrder
  assert(orderId > 0, `bot 4: order #${orderId} for 10 diamonds at $5`)
  await closeWindow(d)
  const placed = await rcon(`treasure place ${TX} ${Y} ${Z}`)
  assert(/Treasure hidden/.test(placed), 'treasure chest placed again')
  assertEq(await countEntities(holoSel(holoId)), 1, 'hologram display present (saved along)')
  const saved = await rcon('save-all flush')
  assert(/Saved the game/i.test(saved), `save-all flush (${saved.trim().slice(-40)})`)
  await sleep(2500)

  // state on disk
  const sA = await sellInfo(A)
  const ahB = await ahInfo(B)
  const moneyB = await walletCents(B)
  const oC = await ordersInfo(C)
  const oD = await ordersInfo(D)
  assertEq([await countItem(A, 'minecraft:wheat'), await countItem(B, 'minecraft:amethyst_shard'), await countItem(C, 'minecraft:diamond')],
    [64, 16, 4], 'initial state saved: 64 wheat, 16 amethyst, 4 diamonds')

  // transactions just before the crash
  const sellWin = await expectWindow(a, () => cmd(a, '/sell'), 8000)
  await sleep(300)
  await shiftClick(a, hotbar(sellWin, 0))
  await sleep(500)
  assertEq(await countItem(A, 'minecraft:wheat'), 0, 'bot 1: 64 wheat in the sell GUI')

  const listed = waitForMessage(b, /Listed (\d+)x/, 15000).then((m) => Number(m[1]), () => 0)
  await ahSell(b, '999')
  assertEq(await listed, 16, 'bot 2: 16 amethyst listed')
  const flipped = waitForMessage(b, /Coinflip for \$100\.00 created/, 6000).then(() => true, () => false)
  await cmd(b, '/coinflip 100')
  assert(await flipped, 'bot 2: open coinflip')

  const main = await expectWindow(c, () => cmd(c, '/orders'))
  const oslot = findSlot(main, (it) => itemLore(it).some((l) => l.includes(`Order #${orderId}`)))
  assert(oslot >= 0, `bot 3: order #${orderId} in the overview`)
  let net = 0
  if (oslot >= 0) {
    const deliver = await expectWindow(c, () => clickSlot(c, oslot))
    assert(new RegExp(`Deliver – Order #${orderId}`).test(windowTitle(deliver)), 'bot 3: delivery window')
    const delivered = waitForMessage(c, /Delivered: (\d+)× .* for \$(\d[\d,]*\.\d{2})/, 8000).then((m) => m, () => null)
    await clickSlot(c, 14)
    const dm = await delivered
    assert(!!dm && dm[1] === '4', `bot 3: 4 diamonds delivered${dm ? ` for $${dm[2]}` : ''}`)
    net = dm ? Math.round(Number(dm[2].replace(/,/g, '')) * 100) : 0
  }

  const d0 = await casino(D)
  await expectWindow(d, () => cmd(d, `/mines ${usd(STAKE)} ${BOMBS}`))
  const p = await casinoPeek(D)
  const safe = [...Array(25).keys()].find((t) => !p.minesList.includes(t))
  await clickSlot(d, minesSlot(safe))
  assert(!!(await waitSlot(d, minesSlot(safe), /Safe/, 3000)), 'bot 4: mines field revealed safely')

  // Save point: without it everything after the crash would be "long before", since the mod writes only with
  // vanilla saves.
  const savedAfter = await rcon('save-all flush')
  assert(/Saved the game/i.test(savedAfter), `save-all flush after the transactions (${savedAfter.trim().slice(-40)})`)
  await sleep(500)

  bots = await restart(true)
  assert(true, 'server restarted after kill -9')
  await sleep(3000)

  // bot 1: wheat unsold, back exactly once (journal or saved inventory, never both)
  assertEq(await countItem(A, 'minecraft:wheat'), 64, 'crash: bot 1 has exactly 64 wheat (no loss, no dupe)')
  const sA1 = await sellInfo(A)
  assertEq([sA1.balance, sA1.items], [sA.balance, sA.items], 'crash: nothing sold, balance the same')
  const restored = await waitForMessage(bots[A], /sell menu was interrupted by a restart/, 8000, { includePast: true })
    .then(() => true, () => false)
  assert(restored, 'crash: hint about the returned sell menu')

  const listedNow = (await ahInfo(B)).active - ahB.active
  const shards = await countItem(B, 'minecraft:amethyst_shard')
  assert(listedNow === 0 || listedNow === 1, `crash: at most the one new listing (${listedNow})`)
  assertEq(shards + 16 * listedNow, 16, `crash: amethyst in inventory (${shards}) + in AH (${16 * listedNow}) = 16`)
  assertEq(await walletCents(B), moneyB, 'crash: coinflip stake refunded or never charged')
  assert(!new RegExp(`creator=${B} `).test(await rcon('minigames status')), 'crash: no open coinflip')

  // bot 3 and bot 4: delivery all or nothing
  const oC1 = await ordersInfo(C)
  const oD1 = await ordersInfo(D)
  const claimed = oD1.claim - oD.claim
  const left = await countItem(C, 'minecraft:diamond')
  assert(claimed === 0 || claimed === 4, `crash: delivery all or nothing (collection chest +${claimed})`)
  assertEq(left + claimed, 4, `crash: diamonds at the supplier (${left}) + in the collection chest (${claimed}) = 4`)
  if (claimed === 4) {
    assertEq([oC1.balance - oC.balance, oD.escrow - oD1.escrow], [net, 2000], 'crash: delivery paid, escrow -$20')
  } else {
    assertEq([oC1.balance - oC.balance, oD.escrow - oD1.escrow], [0, 0], 'crash: no delivery, no money moved')
  }

  const d1 = await casino(D)
  assertEq(d1.balance, d0.balance - STAKE + minesPayout(STAKE, BOMBS, 1), 'crash: mines round paid exactly once (1 field)')
  assertEq([d1.open, d1.pending], ['none', 0], 'crash: no open round')

  // hologram: old saved display removed, exactly one new; chest gone
  await rcon(`tp ${D} ${X + 0.5} ${Y} ${Z + 3.5}`)
  await waitFor(async () => /passed/i.test(await rcon(`execute if loaded ${X} ${Y} ${Z}`)), 30000)
  const back = await waitFor(async () => (await countEntities(holoSel(holoId))) >= 1, 15000)
  assert(!!back, 'crash: hologram display present')
  await sleep(2500)
  assertEq(await countEntities(holoSel(holoId)), 1, 'crash: exactly one display (old one removed from the chunk)')
  assertEq(await countEntities(HOLO_HERE), 1, 'crash: no further displays in the test area')
  await rcon(`forceload add ${X - 16} ${Z - 16} ${X + 16} ${Z + 16}`)
  assert(!(await chestAt(TX, Y, Z)), 'crash: treasure chest removed on start')
  assert(!new RegExp(`active=true x=${TX} y=${Y} z=${Z}`).test(await rcon('treasure info')), 'crash: treasure hunt ended')
  return bots
}

runE2E(async () => {
  let bots = await connectAll()
  try {
    await prepareArea()
    await tpAll()
    await rcon(`deop ${D}`)
    await removeOldHolos()
    await rcon('treasure stop')
    await rcon('news clear')
    await resetState(bots)
    bots = await gracefulStop(bots)
    bots = await crash(bots)
  } finally {
    await cleanup()
  }
}, { timeoutMs: 900000 })
