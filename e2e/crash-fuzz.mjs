// Crash fuzz for all item and money paths. The full run happens on request ("node crash-fuzz.mjs" or "node
// run-all.mjs crash-fuzz"); each round restarts the server about 18 times with kill -9. run-all without a name
// runs it as a rotation (see run-all.mjs). Env: FUZZ_SEED, FUZZ_ONLY, FUZZ_ROTATE, FUZZ_ROTATE_AT. Each path is
// prepared, saved, run up to its confirmation and killed after a random delay; after the restart the state must
// be all before or all after, with no loss or duplicate.
import { Rcon } from 'rcon-client'
import {
  connectBot, rcon, moduleEnabled, closeRcon, assert, assertEq, sleep, runE2E, waitForMessage,
  expectWindow, clickSlot, shiftClick, findSlot, itemLore, windowTitle, stripColors, treasuryFlows,
  treasuryOtherNet, Vec3, HOST, RCON_PORT, RCON_PASSWORD, closeWindow, cmd, countItem, hotbar, walletCents,
} from './lib.mjs'
import { join } from 'node:path'
import { serverPid, killServer, startServer } from '../tools/server-restart.mjs'

const A = 'crashBot1' // /sell (hand, all, GUI)
const B = 'crashBot2' // auction seller (listing, collection box, sold to D)
const C = 'crashBot3' // supplier (order, mission)
const D = 'crashBot4' // buyer (shop, auction), order owner, crates, treasure
const BOTS = [A, B, C, D]
const X = -5300, Y = 230, Z = 5300 // own test area (restart.mjs: -4700/4700, minigames 4600, missions 4300)
const CHEST = { x: X + 4, y: Y, z: Z + 4 } // treasure chest, D stands next to it
const SPOTS = { [A]: [X + 0.5, Z + 0.5], [B]: [X + 2.5, Z + 0.5], [C]: [X - 1.5, Z + 0.5], [D]: [X + 4.5, Z + 2.5] }
const AH_PRICE = 123400 // $1,234 for 3 prismarine shards, far above any NPC buy price
const ORDER_UNIT = 500 // $5 per diamond
const SAVED_AFTER_MS = 2500 // from this delay on: wait for save-all, then kill; must be "after"

const listEnv = (v) => (v ? v.split(',').map((s) => s.trim()).filter(Boolean) : null)
const ROTATE = Math.max(0, Math.floor(Number(process.env.FUZZ_ROTATE ?? 0)) || 0)
const ROTATE_AT = Math.max(0, Math.floor(Number(process.env.FUZZ_ROTATE_AT ?? Math.floor(Date.now() / 86400000))) || 0)
const DELAYS = (listEnv(process.env.FUZZ_DELAYS) ?? (ROTATE > 0 ? ['0', '2500'] : ['0', '150', '600', '1500', '2500', '4000'])).map(Number)
const SAVE_ALL_DELAYS = [0, 25, 50, 75, 100]
const ROUNDS = Math.max(1, Number(process.env.FUZZ_ROUNDS ?? 1))
const SEED = Number(process.env.FUZZ_SEED ?? (ROTATE > 0 ? ROTATE_AT : Date.now() % 2147483647))
const ONLY = listEnv(process.env.FUZZ_ONLY)
const SAVE_ALL = process.env.FUZZ_SAVEALL !== '0'
const SAVE_ALL_ONLY = process.env.FUZZ_SAVEALL === 'only'

function mulberry32(a) {
  return () => {
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rnd = mulberry32(SEED)
const pick = (arr) => arr[Math.floor(rnd() * arr.length)]
function shuffle(arr) {
  const a = [...arr]
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rnd() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]]
  }
  return a
}

const cents = (s) => Math.round(Number(String(s).replace(/[$,]/g, '')) * 100)
const usd = (c) => (c / 100).toFixed(2)

function kv(line) {
  const out = {}
  for (const m of stripColors(line).matchAll(/(\w+)=(\S*)/g)) out[m[1]] = /^-?\d+$/.test(m[2]) ? Number(m[2]) : m[2]
  return out
}

async function sellInfo(name) {
  const v = kv(await rcon(`smpeco sell balance ${name}`))
  if (v.balance === undefined) throw new Error(`smpeco sell balance ${name}`)
  return v
}

async function price(item, name) {
  const out = await rcon(`smpeco price ${item} ${name}`)
  const s = out.match(/\bsell=(-?\d+)/)
  const b = out.match(/\bbuy=(-?\d+)/)
  if (!s || !b) throw new Error(`smpeco price ${item} ${name}: ${out}`)
  return { sell: Number(s[1]), buy: Number(b[1]) }
}

async function ahInfo(name) {
  const m = (await rcon(`ah admin info ${name}`)).match(/active=(\d+) claims=(\d+) balance=(\d+)/)
  if (!m) throw new Error(`ah admin info ${name}`)
  return { active: Number(m[1]), claims: Number(m[2]) }
}

async function ahTaxPercent() {
  const m = (await rcon('ah admin info')).match(/tax=([\d.]+)/)
  if (!m) throw new Error('ah admin info: no tax')
  return Number(m[1])
}

async function ordersInfo(name) {
  const m = (await rcon(`orders admin info ${name}`)).match(/balance=(\d+) escrow=(\d+) orders=(\d+) claim=(\d+) ids=(\S+)/)
  if (!m) throw new Error(`orders admin info ${name}`)
  return { escrow: Number(m[2]), orders: Number(m[3]), claim: Number(m[4]), ids: m[5] === '-' ? [] : m[5].split(',').map(Number) }
}

async function crates(name) {
  if (!CRATES_ON) return { common: 0, rare: 0, epic: 0, pending: 0 }
  const m = (await rcon(`crates info ${name}`)).match(/common=(\d+) rare=(\d+) epic=(\d+) pending=(\d+)/)
  if (!m) throw new Error(`crates info ${name}`)
  return { common: Number(m[1]), rare: Number(m[2]), epic: Number(m[3]), pending: Number(m[4]) }
}

async function shopRecent(name) {
  const m = (await rcon(`smpeco shop info ${name}`)).match(/recent=(\S*)/)
  if (!m) throw new Error(`smpeco shop info ${name}`)
  return m[1]
}

async function mission(name) {
  const out = await rcon(`smpeco missions status ${name}`)
  const lines = out.split('\n').map((l) => l.trim())
  const head = lines.find((l) => l.startsWith('missions status'))
  if (!head) throw new Error(`smpeco missions status ${name}: ${out}`)
  const act = lines.find((l) => l.startsWith('missions active'))
  const a = act ? kv(act) : null
  return {
    earned: kv(head).earned,
    progress: a ? Number(String(a.progress).split('/')[0]) : -1,
    paid: a ? a.paid : -1,
    perItem: a ? a.perItem : -1,
  }
}

async function elite(name) {
  const out = await rcon(`smpeco missions elite status ${name}`)
  const line = out.split('\n').map((l) => l.trim()).find((l) => l.startsWith('missions elite status'))
  if (!line) throw new Error(`smpeco missions elite status ${name}: ${out}`)
  const v = kv(line)
  return { done: v.done === 'true', paid: v.paid, payout: v.payout, id: v.id }
}

async function shopIncome() {
  return (await treasuryFlows()).flows.shop?.income ?? 0
}

async function feeIncome() {
  return (await treasuryFlows()).flows.order_fee?.income ?? 0
}

async function countEntities(selector) {
  const out = await rcon(`execute if entity ${selector}`)
  const m = out.match(/count:?\s*(\d+)/i)
  if (m) return Number(m[1])
  if (/failed/i.test(out)) return 0
  throw new Error(`execute if entity ${selector}: ${out}`)
}

async function waitFor(fn, timeoutMs, stepMs = 200) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const v = await fn()
    if (v) return v
    if (Date.now() > end) return v
    await sleep(stepMs)
  }
}

/**
 * Listener starts at once; throws a readable message on await if nothing comes. If the caller aborts first, the
 * later rejection has no effect (no "unhandled rejection" that would end the process).
 */
function expectMsg(bot, regex, what, timeoutMs = 8000) {
  const p = waitForMessage(bot, regex, timeoutMs).catch(() => { throw new Error(`${bot.username}: no message "${what}" (${regex})`) })
  p.catch(() => {})
  return p
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
  if (!(await waitFor(async () => (await countItem(name, item)) >= n, 5000))) throw new Error(`give ${name} ${item} ${n} had no effect`)
}

/** /ah sell <total price>: stacks show a preview with [Confirm] first and list on repeat. */
async function ahSell(bot, amount, count) {
  const listed = expectMsg(bot, new RegExp(`Listed ${count}x`), `Listed ${count}x`, 15000)
  const first = waitForMessage(bot, /Listed|\[Confirm\]/, 8000).then((m) => m[0], () => null)
  await cmd(bot, `/ah sell ${amount}`)
  if ((await first) === '[Confirm]') await cmd(bot, `/ah sell ${amount}`)
  await listed
}

async function createOrder(bot, item, amount, unitCents) {
  const dialog = await expectWindow(bot, () => cmd(bot, `/orders create ${item} ${amount} ${usd(unitCents)}`))
  if (!/Create Order/.test(windowTitle(dialog))) throw new Error(`expected order confirmation, was "${windowTitle(dialog)}"`)
  const created = expectMsg(bot, /Order #(\d+) created/, 'order created')
  await clickSlot(bot, 9)
  const id = Number((await created)[1])
  await closeWindow(bot)
  return id
}

async function deliverOrder(bot, orderId) {
  const main = await expectWindow(bot, () => cmd(bot, '/orders'))
  const slot = findSlot(main, (it) => itemLore(it).some((l) => l.includes(`Order #${orderId}`)))
  if (slot < 0) throw new Error(`order #${orderId} not in the overview`)
  const win = await expectWindow(bot, () => clickSlot(bot, slot))
  if (!new RegExp(`Deliver – Order #${orderId}`).test(windowTitle(win))) throw new Error(`expected delivery window, was "${windowTitle(win)}"`)
  const done = expectMsg(bot, /Delivered: (\d+)× .* for \$(\d[\d,]*\.\d{2})(?: \(fee \$(\d[\d,]*\.\d{2})\))?/, 'delivered')
  await clickSlot(bot, 14)
  const m = await done
  return { count: Number(m[1]), net: cents(m[2]), fee: m[3] ? cents(m[3]) : 0 }
}

/**
 * Each path: prepare (before "save-all flush"), snap (conservation values of the path; the frame measures money
 * and item totals of all bots), act (up to the confirmation, nothing after), expect (values after the
 * transaction). Optional: settle (after the restart, such as waiting for a join delivery), beforeExtra (values
 * known only after act and 0 before), check.
 */
const TXS = [
  {
    id: 'sell-hand',
    async prepare(b) {
      await give(A, 'minecraft:wheat', 64)
      await hold(b[A], 'wheat')
      return { unit: (await price('minecraft:wheat', A)).sell }
    },
    async snap() {
      const s = await sellInfo(A)
      return { 'wheat:A': await countItem(A, 'minecraft:wheat'), 'sell.earned:A': s.earned, 'sell.items:A': s.items }
    },
    async act(b) {
      const m = expectMsg(b[A], /Sold 64 items for \$([\d,]+\.\d\d)/, 'Sold 64 items')
      await cmd(b[A], '/sell hand')
      return { total: cents((await m)[1]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${A}`]: s[`money:${A}`] + info.total, [`items:${A}`]: s[`items:${A}`] - 64, 'wheat:A': 0,
      'sell.earned:A': s['sell.earned:A'] + info.total, 'sell.items:A': s['sell.items:A'] + 64,
    }),
    check: (ctx, info) => [info.total === 64 * ctx.unit, `proceeds ${info.total} = 64 x ${ctx.unit}`],
  },
  {
    id: 'sell-all',
    async prepare() {
      await give(A, 'minecraft:wheat', 64)
      await give(A, 'minecraft:cobblestone', 32)
      return { expected: 64 * (await price('minecraft:wheat', A)).sell + 32 * (await price('minecraft:cobblestone', A)).sell }
    },
    async snap() {
      const s = await sellInfo(A)
      return {
        'wheat:A': await countItem(A, 'minecraft:wheat'), 'cobblestone:A': await countItem(A, 'minecraft:cobblestone'),
        'sell.earned:A': s.earned, 'sell.items:A': s.items,
      }
    },
    async act(b) {
      const preview = expectMsg(b[A], /Sell all sellable items \(96\) for/, 'preview 96 items')
      await cmd(b[A], '/sell all')
      await preview
      const m = expectMsg(b[A], /Sold 96 items for \$([\d,]+\.\d\d)/, 'Sold 96 items')
      await cmd(b[A], '/sell all')
      return { total: cents((await m)[1]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${A}`]: s[`money:${A}`] + info.total, [`items:${A}`]: s[`items:${A}`] - 96, 'wheat:A': 0, 'cobblestone:A': 0,
      'sell.earned:A': s['sell.earned:A'] + info.total, 'sell.items:A': s['sell.items:A'] + 96,
    }),
    check: (ctx, info) => [info.total === ctx.expected, `proceeds ${info.total} = ${ctx.expected}`],
  },
  {
    id: 'sell-gui',
    async prepare() {
      await give(A, 'minecraft:wheat', 64)
      return { unit: (await price('minecraft:wheat', A)).sell }
    },
    async snap() {
      const s = await sellInfo(A)
      return { 'wheat:A': await countItem(A, 'minecraft:wheat'), 'sell.earned:A': s.earned, 'sell.items:A': s.items }
    },
    async act(b) {
      const a = b[A]
      const win = await expectWindow(a, () => cmd(a, '/sell'), 8000)
      await sleep(300)
      await shiftClick(a, hotbar(win, 0))
      await sleep(500)
      const m = expectMsg(a, /Sold 64 items for \$([\d,]+\.\d\d)/, 'Sold 64 items (close)')
      a.closeWindow(a.currentWindow ?? win)
      return { total: cents((await m)[1]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${A}`]: s[`money:${A}`] + info.total, [`items:${A}`]: s[`items:${A}`] - 64, 'wheat:A': 0,
      'sell.earned:A': s['sell.earned:A'] + info.total, 'sell.items:A': s['sell.items:A'] + 64,
    }),
    // wait for the journal return on join, in case the disk knows the open GUI
    settle: async () => { await sleep(1500) },
    check: (ctx, info) => [info.total === 64 * ctx.unit, `proceeds ${info.total} = 64 x ${ctx.unit}`],
  },
  {
    id: 'shop-buy',
    async prepare() {
      await rcon(`eco set ${D} 1000`)
      return { unit: (await price('minecraft:stone', D)).buy }
    },
    async snap() {
      return { 'stone:D': await countItem(D, 'minecraft:stone'), 'shop.recent:D': await shopRecent(D), 'treasury.shop': await shopIncome() }
    },
    async act(b) {
      const d = b[D]
      const cat = await expectWindow(d, () => cmd(d, '/shop blocks'))
      const slot = findSlot(cat, 'stone', { to: 45 })
      if (slot < 0) throw new Error('stone not in "Building Blocks"')
      const dlg = await expectWindow(d, () => clickSlot(d, slot))
      if (!/Buy/.test(windowTitle(dlg))) throw new Error(`expected buy dialog, was "${windowTitle(dlg)}"`)
      await clickSlot(d, 15)
      await sleep(300)
      const m = expectMsg(d, /Bought 64× .* for \$([\d,]+\.\d\d)/, 'Bought 64×')
      await clickSlot(d, 49)
      return { total: cents((await m)[1]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${D}`]: s[`money:${D}`] - info.total, [`items:${D}`]: s[`items:${D}`] + 64, 'stone:D': s['stone:D'] + 64,
      'shop.recent:D': 'minecraft:stone:64', 'treasury.shop': s['treasury.shop'] + Math.floor(info.total / 2),
    }),
    check: (ctx, info) => [info.total === 64 * ctx.unit, `price ${info.total} = 64 x ${ctx.unit}`],
  },
  {
    id: 'shop-sell',
    async prepare() {
      await give(D, 'minecraft:stone', 64)
      return { unit: (await price('minecraft:stone', D)).sell }
    },
    async snap() {
      return { 'stone:D': await countItem(D, 'minecraft:stone') }
    },
    async act(b) {
      const d = b[D]
      const cat = await expectWindow(d, () => cmd(d, '/shop blocks'))
      const slot = findSlot(cat, 'stone', { to: 45 })
      if (slot < 0) throw new Error('stone not in "Building Blocks"')
      const dlg = await expectWindow(d, () => clickSlot(d, slot, 1, 0))
      if (!/Sell/.test(windowTitle(dlg))) throw new Error(`expected sell dialog, was "${windowTitle(dlg)}"`)
      const m = expectMsg(d, /Sold 64× .* for \$([\d,]+\.\d\d)/, 'Sold 64×')
      await clickSlot(d, 49)
      return { total: cents((await m)[1]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${D}`]: s[`money:${D}`] + info.total, [`items:${D}`]: s[`items:${D}`] - 64, 'stone:D': 0,
    }),
    check: (ctx, info) => [info.total === 64 * ctx.unit, `proceeds ${info.total} = 64 x ${ctx.unit}`],
  },
  {
    id: 'ah-sell',
    async prepare(b) {
      await give(B, 'minecraft:amethyst_shard', 16)
      await hold(b[B], 'amethyst_shard')
      return {}
    },
    async snap() {
      return { 'amethyst:B': await countItem(B, 'minecraft:amethyst_shard'), 'ah.active:B': (await ahInfo(B)).active }
    },
    async act(b) {
      await ahSell(b[B], '999', 16)
      return {}
    },
    expect: (s) => ({ [`items:${B}`]: s[`items:${B}`] - 16, 'amethyst:B': 0, 'ah.active:B': s['ah.active:B'] + 1 }),
  },
  {
    id: 'ah-buy',
    async prepare(b) {
      await give(B, 'minecraft:prismarine_shard', 3)
      await hold(b[B], 'prismarine_shard')
      await ahSell(b[B], usd(AH_PRICE), 3)
      await rcon(`eco set ${D} 2000`)
      return { tax: Math.floor(AH_PRICE * (await ahTaxPercent()) / 100) }
    },
    async snap() {
      return { 'prismarine:D': await countItem(D, 'minecraft:prismarine_shard'), 'ah.active:B': (await ahInfo(B)).active }
    },
    async act(b) {
      const d = b[D]
      const win = await expectWindow(d, () => cmd(d, '/ah search prismarine'))
      const slot = findSlot(win, (it) => it.name === 'prismarine_shard' && itemLore(it).some((l) => l.includes(`Seller: ${B}`)), { to: 45 })
      if (slot < 0) throw new Error(`listing by ${B} not in the auction house`)
      const dialog = await expectWindow(d, () => clickSlot(d, slot))
      if (!/Confirm Purchase/.test(windowTitle(dialog))) throw new Error(`expected buy dialog, was "${windowTitle(dialog)}"`)
      const m = expectMsg(d, /Bought (\d+)x/, 'Bought 3x')
      await clickSlot(d, slot === 9 ? 10 : 9)
      if ((await m)[1] !== '3') throw new Error(`expected 3 shards, bought: ${(await m)[1]}`)
      return {}
    },
    expect: (s, ctx) => ({
      [`money:${D}`]: s[`money:${D}`] - AH_PRICE, [`money:${B}`]: s[`money:${B}`] + AH_PRICE - ctx.tax,
      [`items:${D}`]: s[`items:${D}`] + 3, 'prismarine:D': s['prismarine:D'] + 3, 'ah.active:B': s['ah.active:B'] - 1,
    }),
  },
  {
    id: 'ah-claim',
    async prepare(b) {
      await give(B, 'minecraft:rabbit_hide', 7)
      await hold(b[B], 'rabbit_hide')
      await ahSell(b[B], '500', 7)
      const ex = await rcon(`ah admin expire ${B}`)
      if (!/expired=1/.test(ex)) throw new Error(`ah admin expire ${B}: ${ex.trim()}`)
      if ((await ahInfo(B)).claims !== 1) throw new Error('collection box: expected 1 entry')
      return {}
    },
    async snap() {
      return { 'rabbit_hide:B': await countItem(B, 'minecraft:rabbit_hide'), 'ah.pickup:B': (await ahInfo(B)).claims }
    },
    async act(b) {
      const bb = b[B]
      const win = await expectWindow(bb, () => cmd(bb, '/ah my'))
      const slot = findSlot(win, (it) => it.name === 'rabbit_hide' && itemLore(it).some((l) => /Expired/.test(l)), { to: 45 })
      if (slot < 0) throw new Error('rabbit hide not "Expired" in the collection box')
      const m = expectMsg(bb, /Claimed\./, 'Claimed.')
      await clickSlot(bb, slot)
      await m
      return {}
    },
    expect: (s) => ({ [`items:${B}`]: s[`items:${B}`] + 7, 'rabbit_hide:B': s['rabbit_hide:B'] + 7, 'ah.pickup:B': s['ah.pickup:B'] - 1 }),
  },
  {
    id: 'ah-cancel',
    async prepare(b) {
      await give(B, 'minecraft:feather', 9)
      await hold(b[B], 'feather')
      await ahSell(b[B], usd(AH_PRICE), 9) // far above any NPC buy price: no NPC buyer in between
      return {}
    },
    async snap() {
      const i = await ahInfo(B)
      return { 'feather:B': await countItem(B, 'minecraft:feather'), 'ah.active:B': i.active, 'ah.pickup:B': i.claims }
    },
    async act(b) {
      const bb = b[B]
      const win = await expectWindow(bb, () => cmd(bb, '/ah my'))
      const slot = findSlot(win, (it) => it.name === 'feather' && itemLore(it).some((l) => /Click to cancel/.test(l)), { to: 45 })
      if (slot < 0) throw new Error('feathers not an active listing in "My Auctions"')
      const dialog = await expectWindow(bb, () => clickSlot(bb, slot))
      if (!/Cancel Listing/.test(windowTitle(dialog))) throw new Error(`expected cancel dialog, was "${windowTitle(dialog)}"`)
      const m = expectMsg(bb, /Listing cancelled .* back in your inventory/, 'Listing cancelled')
      await clickSlot(bb, slot === 9 ? 10 : 9)
      await m
      return {}
    },
    // collection box stays the same: with room (OK) items go straight back, never to the box (STORED)
    expect: (s) => ({ [`items:${B}`]: s[`items:${B}`] + 9, 'feather:B': s['feather:B'] + 9, 'ah.active:B': s['ah.active:B'] - 1 }),
  },
  {
    id: 'order-create',
    async prepare() {
      await rcon(`eco set ${D} 1000`)
      return {}
    },
    async snap() {
      const o = await ordersInfo(D)
      return { 'order.escrow:D': o.escrow, 'order.count:D': o.orders }
    },
    async act(b) {
      const d = b[D]
      const dialog = await expectWindow(d, () => cmd(d, `/orders create minecraft:diamond 8 ${usd(ORDER_UNIT)}`))
      if (!/Create Order/.test(windowTitle(dialog))) throw new Error(`expected order confirmation, was "${windowTitle(dialog)}"`)
      const created = expectMsg(d, /Order #(\d+) created/, 'order created')
      await clickSlot(d, 9)
      return { orderId: Number((await created)[1]) }
    },
    expect: (s) => ({
      [`money:${D}`]: s[`money:${D}`] - 8 * ORDER_UNIT, 'order.escrow:D': s['order.escrow:D'] + 8 * ORDER_UNIT,
      'order.count:D': s['order.count:D'] + 1,
    }),
  },
  {
    id: 'order-cancel',
    async prepare(b) {
      await rcon(`eco set ${D} 1000`)
      const orderId = await createOrder(b[D], 'minecraft:diamond', 6, ORDER_UNIT)
      if ((await ordersInfo(D)).escrow !== 6 * ORDER_UNIT) throw new Error(`escrow ${6 * ORDER_UNIT} expected`)
      return { orderId }
    },
    async snap() {
      const o = await ordersInfo(D)
      return { 'order.escrow:D': o.escrow, 'order.count:D': o.orders }
    },
    async act(b, ctx) {
      const m = expectMsg(b[D], new RegExp(`Order #${ctx.orderId} cancelled\\. Refunded: \\$([\\d,]+\\.\\d\\d)`), `order #${ctx.orderId} cancelled`)
      await cmd(b[D], `/orders cancel ${ctx.orderId}`)
      return { refunded: cents((await m)[1]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${D}`]: s[`money:${D}`] + info.refunded, 'order.escrow:D': s['order.escrow:D'] - info.refunded,
      'order.count:D': s['order.count:D'] - 1,
    }),
    check: (ctx, info) => [info.refunded === 6 * ORDER_UNIT, `refunded ${info.refunded} = 6 × ${ORDER_UNIT}`],
  },
  {
    id: 'order-deliver',
    async prepare(b) {
      await rcon(`eco set ${D} 1000`)
      const orderId = await createOrder(b[D], 'minecraft:diamond', 10, ORDER_UNIT)
      await give(C, 'minecraft:diamond', 4)
      return { orderId }
    },
    async snap() {
      const o = await ordersInfo(D)
      return { 'diamond:C': await countItem(C, 'minecraft:diamond'), 'order.chest:D': o.claim, 'order.escrow:D': o.escrow, 'treasury.fee': await feeIncome() }
    },
    async act(b, ctx) {
      const r = await deliverOrder(b[C], ctx.orderId)
      if (r.count !== 4) throw new Error(`expected 4 diamonds, delivered: ${r.count}`)
      return r
    },
    expect: (s, ctx, info) => ({
      [`money:${C}`]: s[`money:${C}`] + info.net, [`items:${C}`]: s[`items:${C}`] - 4, 'diamond:C': 0,
      'order.chest:D': s['order.chest:D'] + 4, 'order.escrow:D': s['order.escrow:D'] - 4 * ORDER_UNIT,
      'treasury.fee': s['treasury.fee'] + info.fee,
    }),
    check: (ctx, info) => [info.net + info.fee === 4 * ORDER_UNIT, `net ${info.net} + fee ${info.fee} = ${4 * ORDER_UNIT}`],
  },
  {
    id: 'order-claim',
    async prepare(b) {
      await rcon(`eco set ${D} 1000`)
      const orderId = await createOrder(b[D], 'minecraft:diamond', 4, ORDER_UNIT)
      await give(C, 'minecraft:diamond', 4)
      const r = await deliverOrder(b[C], orderId)
      await closeWindow(b[C])
      if (r.count !== 4 || (await ordersInfo(D)).claim !== 4) throw new Error('collection chest: expected 4 diamonds')
      return {}
    },
    async snap() {
      return { 'diamond:D': await countItem(D, 'minecraft:diamond'), 'order.chest:D': (await ordersInfo(D)).claim }
    },
    async act(b) {
      const m = expectMsg(b[D], /Claimed: (\d+) items/, 'Claimed: 4 items')
      await cmd(b[D], '/orders claim')
      if ((await m)[1] !== '4') throw new Error('expected 4 items claimed')
      return {}
    },
    expect: (s) => ({ [`items:${D}`]: s[`items:${D}`] + 4, 'diamond:D': s['diamond:D'] + 4, 'order.chest:D': 0 }),
  },
  {
    id: 'mission-deliver',
    async prepare() {
      const out = await rcon(`smpeco missions assign ${C} medium_iron 10`)
      if (!/assign ok/.test(out)) throw new Error(`smpeco missions assign: ${out.trim()}`)
      await give(C, 'minecraft:iron_ingot', 6)
      const m = await mission(C)
      if (!(m.perItem > 0)) throw new Error(`delivery without a price (${JSON.stringify(m)})`)
      return { perItem: m.perItem }
    },
    async snap() {
      const m = await mission(C)
      return { 'iron:C': await countItem(C, 'minecraft:iron_ingot'), 'mission.progress:C': m.progress, 'mission.paid:C': m.paid, 'mission.earned:C': m.earned }
    },
    async act(b) {
      const c = b[C]
      const win = await expectWindow(c, () => cmd(c, '/missions active'))
      const slot = findSlot(win, /^Deliver$/)
      if (slot < 0) throw new Error('deliver button missing')
      const m = expectMsg(c, /Delivered (\d+)× \(\+\$([\d,]+\.\d\d)\)/, 'Delivered 6×')
      await clickSlot(c, slot)
      const got = await m
      return { count: Number(got[1]), paid: cents(got[2]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${C}`]: s[`money:${C}`] + info.paid, [`items:${C}`]: s[`items:${C}`] - 6, 'iron:C': 0,
      'mission.progress:C': s['mission.progress:C'] + 6, 'mission.paid:C': s['mission.paid:C'] + info.paid,
      'mission.earned:C': s['mission.earned:C'] + info.paid,
    }),
    // unit price can drift with market value, so only the message counts exactly (it feeds the expectation)
    check: (ctx, info) => [info.count === 6 && info.paid > 0, `6 delivered for ${info.paid} (before ${ctx.perItem}/piece)`],
  },
  {
    // daily elite delivery: 40 ancient debris at once gives money, epic key and "done today" (all or nothing)
    id: 'elite-deliver',
    async prepare() {
      const out = await rcon('smpeco missions elite set ancient_debris')
      if (!/set ok id=ancient_debris/.test(out)) throw new Error(`smpeco missions elite set: ${out.trim()}`)
      await give(C, 'minecraft:ancient_debris', 40)
      const e = await elite(C)
      if (e.done || !(e.payout > 0)) throw new Error(`daily elite not deliverable (${JSON.stringify(e)})`)
      return { payout: e.payout }
    },
    async snap() {
      const e = await elite(C)
      return {
        'debris:C': await countItem(C, 'minecraft:ancient_debris'), 'elite.done:C': e.done ? 1 : 0, 'elite.paid:C': e.paid,
        'keys.epic:C': (await crates(C)).epic,
      }
    },
    async act(b) {
      const c = b[C]
      await expectWindow(c, () => cmd(c, '/missions'))
      const dialog = await expectWindow(c, () => clickSlot(c, 4))
      if (!/Hand In Daily Elite/.test(windowTitle(dialog))) throw new Error(`expected confirmation, was "${windowTitle(dialog)}"`)
      const m = expectMsg(c, /Daily Elite complete: .*Reward: \$([\d,]+\.\d\d)/, 'Daily Elite complete')
      await clickSlot(c, 9)
      return { paid: cents((await m)[1]) }
    },
    expect: (s, ctx, info) => ({
      [`money:${C}`]: s[`money:${C}`] + info.paid, [`items:${C}`]: s[`items:${C}`] - 40, 'debris:C': 0, 'elite.done:C': 1,
      'elite.paid:C': info.paid, 'keys.epic:C': s['keys.epic:C'] + 1,
    }),
    // payout = min(reward, price cap x amount) can drift with market value, so the message counts exactly
    check: (ctx, info) => [info.paid > 0 && info.paid <= 1_800_000, `paid ${info.paid} (before ${ctx.payout})`],
  },
  {
    id: 'crate',
    async prepare() {
      await rcon(`crates give ${D} common 1`)
      await rcon(`crates force ${D} common diamonds`)
      return {}
    },
    async snap() {
      const k = await crates(D)
      return { 'diamond:D': await countItem(D, 'minecraft:diamond'), 'keys:D': k.common, 'crate.pending:D': k.pending }
    },
    async act(b) {
      const d = b[D]
      const menu = await expectWindow(d, () => cmd(d, '/crates'))
      const slot = findSlot(menu, /Common Crate/)
      if (slot < 0) throw new Error('Common Crate not in the menu')
      const spin = await expectWindow(d, () => clickSlot(d, slot))
      if (!/opening/.test(windowTitle(spin))) throw new Error(`spin animation expected, was "${windowTitle(spin)}"`)
      return {} // confirmation = animation running (key taken, prize stored); delivery after about 3 s
    },
    expect: (s) => ({ [`items:${D}`]: s[`items:${D}`] + 2, 'diamond:D': s['diamond:D'] + 2, 'keys:D': s['keys:D'] - 1 }),
    // a pending prize is delivered on join (2 s), so compare only then
    settle: async () => { await waitFor(async () => (await crates(D)).pending === 0, 10000, 500) },
  },
  {
    // pending crate prize (inventory was full), delivered automatically on join (CratesFeature JOIN to
    // deliverPending)
    id: 'crate-join',
    async prepare(b) {
      const d = b[D]
      await rcon(`crates give ${D} common 1`)
      await rcon(`crates force ${D} common diamonds`)
      await give(D, 'minecraft:stone', 36 * 64) // main inventory full: the prize stays pending, never at the feet
      const menu = await expectWindow(d, () => cmd(d, '/crates'))
      const slot = findSlot(menu, /Common Crate/)
      if (slot < 0) throw new Error('Common Crate not in the menu')
      const waiting = expectMsg(d, /Your reward is waiting/, 'Your reward is waiting', 15000)
      await expectWindow(d, () => clickSlot(d, slot))
      await waiting
      await closeWindow(d)
      await rcon(`clear ${D} minecraft:stone`)
      const k = await crates(D)
      if (k.pending !== 1) throw new Error(`expected 1 pending crate prize, pending=${k.pending}`)
      return {}
    },
    async snap() {
      const k = await crates(D)
      return { 'diamond:D': await countItem(D, 'minecraft:diamond'), 'crate.pending:D': k.pending, 'keys:D': k.common }
    },
    async act(b) {
      // log out and reconnect: delivery on join (2 s after it) is the transaction
      b[D].quit()
      if (!(await waitFor(async () => !(await rcon('list')).includes(D), 10000, 200))) throw new Error(`${D}: logout had no effect`)
      const d = await connectBot(D)
      b[D] = d // b is live: frame, recover() and cleanup() use the new bot
      await waitForMessage(d, /Crate reward received/, 10000, { includePast: true })
        .catch(() => { throw new Error(`${D}: no message "Crate reward received" after the join`) })
      return {}
    },
    expect: (s) => ({ [`items:${D}`]: s[`items:${D}`] + 2, 'diamond:D': s['diamond:D'] + 2, 'crate.pending:D': s['crate.pending:D'] - 1 }),
    // "before" on disk means delivered again on join after the restart, exactly once; compare only then
    settle: async () => { await waitFor(async () => (await crates(D)).pending === 0, 10000, 500) },
  },
  {
    id: 'treasure',
    async prepare(b) {
      await rcon('treasure stop')
      await rcon(`setblock ${CHEST.x} ${CHEST.y} ${CHEST.z} minecraft:air`)
      const placed = await rcon(`treasure place ${CHEST.x} ${CHEST.y} ${CHEST.z}`)
      const pm = placed.match(/money=(\d+)/)
      if (!pm) throw new Error(`treasure place: ${placed.trim()}`)
      const info = kv((await rcon('treasure info')).match(/treasure active=true.*/)?.[0] ?? '')
      if (info.x !== CHEST.x) throw new Error('treasure hunt not active')
      const [common, rare] = String(info.keys).split('/').map(Number)
      await waitFor(() => b[D].blockAt(new Vec3(CHEST.x, CHEST.y, CHEST.z))?.name === 'chest', 5000, 100)
      return { money: Number(pm[1]), common, rare }
    },
    async snap() {
      const k = await crates(D)
      return { 'keys:D': k.common, 'keys.rare:D': k.rare }
    },
    async act(b) {
      const d = b[D]
      const win = await expectWindow(d, () => d.activateBlock(d.blockAt(new Vec3(CHEST.x, CHEST.y, CHEST.z))))
      if (!/Treasure found/.test(windowTitle(win))) throw new Error(`expected reward GUI, was "${windowTitle(win)}"`)
      const items = {}
      for (let i = 10; i <= 16; i++) {
        const it = win.slots[i]
        const lore = it ? itemLore(it) : []
        if (!it || !lore.some((l) => /Put into your inventory/.test(l))) continue
        const n = lore.map((l) => l.match(/Amount: ([\d,]+)/)).find(Boolean)
        items[`minecraft:${it.name}`] = n ? Number(n[1].replace(/,/g, '')) : it.count
      }
      return { items }
    },
    beforeExtra: (info) => Object.fromEntries(Object.keys(info.items).map((id) => [`treasure:${id}`, 0])),
    async snapAfter(info) {
      const out = {}
      for (const id of Object.keys(info.items)) out[`treasure:${id}`] = await countItem(D, id)
      return out
    },
    expect: (s, ctx, info) => {
      const total = Object.values(info.items).reduce((a, n) => a + n, 0)
      const e = {
        [`money:${D}`]: s[`money:${D}`] + ctx.money, [`items:${D}`]: s[`items:${D}`] + total,
        'keys:D': s['keys:D'] + ctx.common, 'keys.rare:D': s['keys.rare:D'] + ctx.rare,
      }
      for (const [id, n] of Object.entries(info.items)) e[`treasure:${id}`] = n
      return e
    },
  },
]

// for the save-all variant: paths where player file and mod data must agree
const SAVE_ALL_TXS = [
  'sell-hand', 'shop-buy', 'shop-sell', 'ah-buy', 'ah-claim', 'ah-cancel', 'order-deliver', 'order-claim', 'mission-deliver',
  'elite-deliver', 'crate-join', 'treasure',
]

let live = {}
const outcomes = {}

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
  await waitFor(async () => /passed/i.test(await rcon(`execute if loaded ${X} ${Y} ${Z}`)), 30000, 500)
  await rcon(`fill ${X - 8} ${Y - 1} ${Z - 8} ${X + 8} ${Y - 1} ${Z + 8} minecraft:glass`)
  await rcon(`fill ${X - 8} ${Y} ${Z - 8} ${X + 8} ${Y + 4} ${Z + 8} minecraft:air`)
}

async function tpAll() {
  for (const [name, [x, z]] of Object.entries(SPOTS)) await rcon(`tp ${name} ${x} ${Y} ${z}`)
  await sleep(800)
}

async function resetState(bots) {
  for (const name of BOTS) await closeWindow(bots[name])
  await tpAll()
  try { await rcon('treasure stop') } catch {}
  for (const name of BOTS) {
    await rcon(`gamemode survival ${name}`)
    await rcon(`ah admin purge ${name}`)
    for (const id of (await ordersInfo(name)).ids) await rcon(`orders admin cancel ${id}`)
    await rcon(`smpeco missions reset ${name}`)
    await rcon(`smpeco shop reset ${name}`)
    for (const tier of ['common', 'rare', 'epic']) await rcon(`crates set ${name} ${tier} 0`)
    await rcon(`clear ${name}`)
  }
  for (const name of BOTS) {
    if ((await ordersInfo(name)).claim > 0) { await cmd(bots[name], '/orders claim'); await sleep(1000) }
    if ((await crates(name)).pending > 0) { await cmd(bots[name], '/crates claim'); await sleep(1000) }
  }
  await rcon(`fill ${X - 8} ${Y} ${Z - 8} ${X + 8} ${Y + 4} ${Z + 8} minecraft:air`)
  await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..24]`)
  for (const name of BOTS) {
    await rcon(`clear ${name}`)
    await rcon(`eco set ${name} 0`)
    bots[name].setQuickBarSlot(0)
  }
  await sleep(500)
  for (const name of BOTS) {
    if ((await countItem(name, '*')) !== 0) throw new Error(`${name}: inventory not empty after cleanup`)
  }
}

async function base() {
  const s = {}
  for (const name of BOTS) {
    s[`money:${name}`] = await walletCents(name)
    s[`items:${name}`] = await countItem(name, '*')
  }
  return s
}

function same(a, b) {
  for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) return false
  return true
}

function describe(actual, before, expected) {
  const out = []
  for (const k of Object.keys(actual)) {
    if (actual[k] !== before[k] || actual[k] !== expected[k]) out.push(`${k}: before=${before[k]} after=${expected[k]} is=${actual[k]}`)
  }
  return out.join('; ')
}

const log = (m) => console.log(`[restart] ${m}`)

async function iteration(step, label) {
  const { tx, delay, saveAll } = step
  let bots = live
  await resetState(bots)
  const ctx = await tx.prepare(bots)
  for (const name of BOTS) await closeWindow(bots[name])
  const saved = await rcon('save-all flush')
  if (!/Saved the game/i.test(saved)) throw new Error(`save-all flush: ${saved.trim().slice(-80)}`)
  await sleep(2500) // wait for the last flushes: disk = memory
  let before = { ...(await base()), ...(await tx.snap(ctx)) }
  const flows0 = await treasuryFlows()
  const pid = serverPid()
  if (!pid) throw new Error('server PID not found')

  let raw = null
  if (saveAll) {
    raw = await Rcon.connect({ host: HOST, port: RCON_PORT, password: RCON_PASSWORD, timeout: 30000 })
    raw.on('error', () => {})
  }
  let info
  try {
    info = await tx.act(bots, ctx)
  } catch (err) {
    if (raw) { try { await raw.end() } catch {} }
    throw err
  }
  // no waiting from here on except the rolled delay
  if (raw) raw.send('save-all').catch(() => {})
  if (delay > 0) await sleep(delay)
  if (!saveAll && delay >= SAVED_AFTER_MS) {
    const out = await rcon('save-all') // wait for the mod's save point, which comes before the player files
    if (!/Saved the game/i.test(out)) throw new Error(`save-all: ${out.trim().slice(-80)}`)
  }
  const killed = killServer({ pid, log }) // kills synchronously on the call
  await killed
  live = {}
  await closeRcon()
  if (raw) { try { await raw.end() } catch {} }

  const [ok, why] = tx.check ? tx.check(ctx, info) : [true, '']
  assert(ok, `${label}: transaction confirmed${why ? ` (${why})` : ''}`)

  await startServer({ log })
  bots = await connectAll()
  await sleep(3500) // join deliveries (crates 2 s, pending messages 1 s, sell journal)
  if (tx.settle) await tx.settle(bots, ctx, info)

  if (tx.beforeExtra) before = { ...before, ...tx.beforeExtra(info) }
  const after = { ...(await base()), ...(await tx.snap(ctx)), ...(tx.snapAfter ? await tx.snapAfter(info) : {}) }
  const expected = { ...before, ...tx.expect(before, ctx, info) }
  if (same(before, expected)) throw new Error(`${tx.id}: expected state equals the initial state (test wrong)`)
  const cls = tx.accept ? tx.accept(after, before, expected) : same(after, before) ? 'before' : same(after, expected) ? 'after' : null
  ;(outcomes[tx.id + (saveAll ? '+save-all' : '')] ??= []).push(`${delay}ms:${cls ?? 'MIXED'}`)
  assert(cls !== null, `${label}: all or nothing, ${cls ?? `MIXED (${describe(after, before, expected)})`}`)
  if (!saveAll && delay >= SAVED_AFTER_MS) assertEq(cls, 'after', `${label}: on disk after waiting for save-all`)

  const flows1 = await treasuryFlows()
  assertEq(flows1.balance - flows0.balance, treasuryOtherNet(flows0, flows1),
    `${label}: treasury balance matches the treasury entries (economy.json, treasury.json)`)
  assertEq(await countEntities(`@e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..24]`), 0, `${label}: no items on the ground`)
  return bots
}

async function recover() {
  for (const bot of Object.values(live)) { try { bot.quit() } catch {} }
  live = {}
  await sleep(1500)
  await closeRcon()
  if (!serverPid()) await startServer({ log })
  await connectAll()
}

async function cleanup() {
  const bots = live
  for (const name of BOTS) await closeWindow(bots[name])
  const steps = ['treasure stop', 'smpeco missions elite clear']
  for (const name of BOTS) {
    steps.push(`ah admin purge ${name}`, `smpeco missions reset ${name}`, `smpeco shop reset ${name}`, `crates set ${name} common 0`)
  }
  for (const s of steps) {
    try { await rcon(s) } catch (err) { console.log(`[E2E] INFO cleanup "${s}" failed: ${err?.message ?? err}`) }
  }
  try {
    for (const name of BOTS) for (const id of (await ordersInfo(name)).ids) await rcon(`orders admin cancel ${id}`)
    for (const name of BOTS) {
      if (bots[name] && (await ordersInfo(name)).claim > 0) { await cmd(bots[name], '/orders claim'); await sleep(1000) }
      if (bots[name] && (await crates(name)).pending > 0) { await cmd(bots[name], '/crates claim'); await sleep(1000) }
    }
    for (const name of BOTS) {
      await rcon(`clear ${name}`)
      await rcon(`eco set ${name} 0`)
    }
    await rcon(`kill @e[type=minecraft:item,x=${X},y=${Y},z=${Z},distance=..24]`)
    await rcon(`fill ${X - 8} ${Y - 1} ${Z - 8} ${X + 8} ${Y + 4} ${Z + 8} minecraft:air`)
    await rcon(`forceload remove ${X - 16} ${Z - 16} ${X + 16} ${Z + 16}`)
  } catch (err) {
    console.log(`[E2E] INFO cleanup failed: ${err?.message ?? err}`)
  }
}

// paths of disabled extra modules drop out (crates: crates, treasure chest: minigames)
const CRATES_ON = await moduleEnabled('crates')
const MINIGAMES_ON = await moduleEnabled('minigames')
const NEEDS = { crate: CRATES_ON, 'crate-join': CRATES_ON, treasure: MINIGAMES_ON }
const selected = TXS.filter((t) => (!ONLY || ONLY.includes(t.id)) && NEEDS[t.id] !== false)
if (selected.length < TXS.length) console.log(`[E2E] INFO crash-fuzz: without ${TXS.filter((t) => !selected.includes(t)).map((t) => t.id).join(', ')} (module off)`)
const saveAllSelected = selected.filter((t) => SAVE_ALL_TXS.includes(t.id))
const plan = []
if (ROTATE > 0) {
  // Rotation (run-all without a name): run n takes paths n*k to n*k+k-1 (cyclic); each path alternates between
  // the delays per cycle, the save-all path rotates on its own. All of it follows from n, so FUZZ_ROTATE_AT=n
  // repeats a run.
  const n = selected.length
  const k = Math.min(ROTATE, n)
  if (!SAVE_ALL_ONLY) {
    for (let i = 0; i < k; i++) {
      const pos = ROTATE_AT * k + i
      const idx = pos % n
      plan.push({ tx: selected[idx], delay: DELAYS[(Math.floor(pos / n) + idx) % DELAYS.length], saveAll: false })
    }
  }
  if (SAVE_ALL && saveAllSelected.length > 0) {
    const tx = saveAllSelected[ROTATE_AT % saveAllSelected.length]
    plan.push({ tx, delay: SAVE_ALL_DELAYS[Math.floor(ROTATE_AT / saveAllSelected.length) % SAVE_ALL_DELAYS.length], saveAll: true })
  }
} else {
  for (let r = 0; r < ROUNDS; r++) {
    if (SAVE_ALL_ONLY) {
      for (const tx of shuffle(saveAllSelected)) plan.push({ tx, delay: pick(SAVE_ALL_DELAYS), saveAll: true })
      continue
    }
    const delays = shuffle(DELAYS)
    shuffle(selected).forEach((tx, i) => plan.push({ tx, delay: delays[i % delays.length], saveAll: false }))
    if (SAVE_ALL && saveAllSelected.length > 0) plan.push({ tx: pick(saveAllSelected), delay: pick(SAVE_ALL_DELAYS), saveAll: true })
  }
}

if (ROTATE > 0) {
  const k = Math.min(ROTATE, selected.length)
  const env = [`FUZZ_ROTATE=${ROTATE}`, `FUZZ_ROTATE_AT=${ROTATE_AT}`]
  for (const name of ['FUZZ_ONLY', 'FUZZ_DELAYS', 'FUZZ_SAVEALL']) if (process.env[name]) env.push(`${name}=${process.env[name]}`)
  console.log(`[E2E] INFO crash-fuzz rotation ${ROTATE_AT}: ${k} of ${selected.length} paths (+ save-all), all paths after `
    + `${Math.ceil(selected.length / Math.max(1, k))} runs; repeat: ${env.join(' ')} node crash-fuzz.mjs`)
} else {
  console.log(`[E2E] INFO crash-fuzz seed=${SEED} rounds=${ROUNDS} passes=${plan.length} (repeat: FUZZ_SEED=${SEED})`)
}
console.log(`[E2E] INFO plan: ${plan.map((p) => `${p.tx.id}@${p.saveAll ? 'save-all+' : ''}${p.delay}`).join(', ')}`)

runE2E(async () => {
  if (plan.length === 0) {
    assert(false, `empty plan: FUZZ_ONLY=${ONLY} matches no path (${TXS.map((t) => t.id).join(', ')}; save-all: ${SAVE_ALL_TXS.join(', ')})`)
    return
  }
  if (!serverPid()) throw new Error('server not running (crash-fuzz needs the local server process)')
  await connectAll()
  try {
    await prepareArea()
    for (const name of BOTS) await rcon(`deop ${name}`)
    let n = 0
    for (const step of plan) {
      n++
      const label = `[${n}/${plan.length}] ${step.tx.id} @ ${step.saveAll ? `save-all + ${step.delay}` : step.delay} ms`
      console.log(`[E2E] INFO ---- ${label}`)
      try {
        await iteration(step, label)
      } catch (err) {
        assert(false, `${label}: Exception ${err?.stack ?? err}`)
        try { await recover() } catch (e) { assert(false, `${label}: server not restored: ${e?.message ?? e}`); break }
      }
    }
  } finally {
    for (const [id, list] of Object.entries(outcomes)) console.log(`[E2E] INFO ${id}: ${list.join(', ')}`)
    await cleanup()
  }
}, { timeoutMs: plan.length * 180000 + 120000 })
