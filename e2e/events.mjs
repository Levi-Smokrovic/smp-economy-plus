// Random events (market day, gold rush, lucky hour) with title and boss bar, trader rank (/rank, hub GUI) and a
// lottery with numbered tickets (1000 per draw, $100, at most 50 per player): buying through GUI and command,
// random distinct numbers, no draw without tickets, rollover, forced numbers on test servers, offline winners,
// payouts owed when the treasury runs short, restarts (E2E_LOTTO_RESTART=1), and the lottery permission.
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import {
  connectBot, rcon, closeRcon, runE2E, assert, assertEq, sleep, waitForMessage, waitForWindow,
  expectWindow, clickSlot, shiftClick, findSlot, itemLabel, itemLore, windowTitle, textOf, treasuryFlows,
  treasuryDelta, cmd, hotbar,
} from './lib.mjs'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'

const B1 = 'eventsBot1'
const B2 = 'eventsBot2'
const MONEY_CMD = process.env.E2E_MONEY_CMD ?? 'eco set {name} {amount}'
const BAR_COLORS = ['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white']

// Slots (EventsGui)
const HUB_EVENT = 11, HUB_RANK = 13, HUB_LOTTO = 15
const LOTTO_JACKPOT = 4, LOTTO_PRIZES = 11, LOTTO_SOLD = 13, LOTTO_CHANCE = 15
const LOTTO_BUY_1 = 19, LOTTO_BUY_5 = 20, LOTTO_BUY_10 = 21, LOTTO_BUY_MAX = 22, LOTTO_MINE = 24, LOTTO_RULES = 25
const LOTTO_HISTORY = 28
const NODE_LOTTO = 'smpeconomy.lottery'
// treasury reasons (Lottery.R_*)
const R_TICKETS = 'events.lotto_tickets'
const R_PRIZES = 'events.lotto_prizes'
const LOTTO_RESTART = process.env.E2E_LOTTO_RESTART === '1'
const EVENTS_CONFIG = join(E2E_SERVER_DIR, 'config', 'smpeconomy', 'events.json')
// original text of events.json (lottoForcedDraws for the test), written back in the finally block
let eventsConfigBefore = null

/** Sets keys in events.json and reloads (/smpeco reload); the first call remembers the original. */
async function setEventsConfig(patch) {
  const text = readFileSync(EVENTS_CONFIG, 'utf8')
  if (eventsConfigBefore === null) eventsConfigBefore = text
  writeFileSync(EVENTS_CONFIG, JSON.stringify({ ...JSON.parse(text), ...patch }, null, 2) + '\n')
  const out = await rcon('smpeco reload')
  console.log(`[E2E] events.json ${JSON.stringify(patch)}: ${out.trim().split('\n')[0]}`)
}

async function restoreEventsConfig() {
  if (eventsConfigBefore === null) return
  writeFileSync(EVENTS_CONFIG, eventsConfigBefore)
  eventsConfigBefore = null
  const out = await rcon('smpeco reload')
  console.log(`[E2E] events.json restored: ${out.trim().split('\n')[0]}`)
}

// record titles and boss bars from packets (1.21.11: NBT components, see textOf)
function track(bot) {
  bot.titles = []
  bot.bars = new Map()
  bot._client.on('set_title_text', (p) => bot.titles.push({ kind: 'title', text: textOf(p.text) }))
  bot._client.on('set_title_subtitle', (p) => bot.titles.push({ kind: 'subtitle', text: textOf(p.text) }))
  bot._client.on('boss_bar', (p) => {
    const id = String(p.entityUUID)
    if (p.action === 0) bot.bars.set(id, { title: textOf(p.title), color: BAR_COLORS[p.color] ?? p.color, health: p.health })
    else if (p.action === 1) bot.bars.delete(id)
    else if (bot.bars.has(id)) {
      const bar = bot.bars.get(id)
      if (p.action === 2) bar.health = p.health
      if (p.action === 3) bar.title = textOf(p.title)
      if (p.action === 4) bar.color = BAR_COLORS[p.color] ?? p.color
    }
  })
  return bot
}

async function until(fn, timeoutMs = 6000, stepMs = 200) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const v = await fn()
    if (v) return v
    await sleep(stepMs)
  }
  return await fn()
}

function eventBar(bot) {
  return [...bot.bars.values()].find((b) => /MARKET DAY|GOLD RUSH|LUCKY HOUR/.test(b.title))
}

async function eventStatus() {
  const out = await rcon('event status')
  const m = out.match(/event active=(\S+) param=(\S+) remaining=(\d+) next=(\d+) jobMult=([\d.]+) chatMult=([\d.]+) luckyFlag=(true|false) bar=(true|false)/)
  if (!m) throw new Error(`event status unreadable: ${out}`)
  return { active: m[1], param: m[2], remaining: Number(m[3]), jobMult: Number(m[5]), chatMult: Number(m[6]), lucky: m[7] === 'true', bar: m[8] === 'true' }
}

/**
 * {@code lottery status [player]}: current draw (jackpot, reason, tickets, sold, open), last draw ({@code
 * last=<id>:<numbers>:<winner|...>:<next jackpot>}) and for a player tickets, numbers, balance, pending prizes.
 */
async function lottoStatus(name) {
  const out = await rcon(name ? `lottery status ${name}` : 'lottery status')
  const m = out.match(/lotto draw=(\d+) jackpot=(\d+) base=(\d+) size=(\d+) sold=(\d+) players=(\d+) next=(\d+) owed=(\d+) pot=(\d+) prizes=(\S*) ready=(true|false)/)
  const c = out.match(/covered=(true|false) need=(\d+) reserve=(\d+) max=(\d+) forcedDraws=(true|false) rollover=(true|false)/)
  if (!m || !c) throw new Error(`lotto status unreadable: ${out}`)
  const l = out.match(/last=(\d+):([\d,]+):([^:\s]+):(\d+):(true|false)/)
  const p = out.match(/playerTickets=(\d+) numbers=(\S+) balance=(\d+) owedTo=(\d+)/)
  return {
    draw: Number(m[1]), jackpot: Number(m[2]), base: Number(m[3]), size: Number(m[4]), sold: Number(m[5]), players: Number(m[6]),
    next: Number(m[7]), owed: Number(m[8]), pot: Number(m[9]), prizes: m[10].split(',').filter(Boolean).map(Number), ready: m[11] === 'true',
    covered: c[1] === 'true', need: Number(c[2]), reserve: Number(c[3]), max: Number(c[4]), forcedDraws: c[5] === 'true', rollover: c[6] === 'true',
    last: l ? { id: Number(l[1]), numbers: l[2].split(',').map(Number), winners: l[3].split('|'), nextJackpot: Number(l[4]), forced: l[5] === 'true' } : null,
    playerTickets: p ? Number(p[1]) : -1, numbers: p && p[2] !== '-' ? p[2].split(',').map(Number) : [],
    balance: p ? Number(p[3]) : -1, owedTo: p ? Number(p[4]) : -1, raw: out,
  }
}

const money = (c) => '$' + (c / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const moneyRe = (c) => money(c).replace(/[$.]/g, (x) => '\\' + x)
const nums = (text) => [...String(text).matchAll(/#(\d+)/g)].map((x) => Number(x[1]))

async function sellMult(item, name) {
  const out = await rcon(`smpeco price ${item} ${name}`)
  const m = out.match(/sellMult=([\d.]+)/)
  if (!m) throw new Error(`smpeco price ${item}: ${out}`)
  return Number(m[1])
}

async function setMoney(name, dollars) {
  const cmd = MONEY_CMD.replace('{name}', name).replace('{amount}', String(dollars))
  const out = await rcon(cmd)
  if (/Unknown|incomplete/i.test(out)) throw new Error(`money command "${cmd}" failed: ${out}`)
  const st = await lottoStatus(name)
  assertEq(st.balance, dollars * 100, `${name} has $${dollars}`)
}

const near = (a, b) => Math.abs(a - b) < 0.005

runE2E(async () => {
  let b1 = track(await connectBot(B1))
  let b2 = track(await connectBot(B2))
  assert(true, 'bots connected')
  await rcon(`deop ${B1}`)
  await rcon(`deop ${B2}`)
  await rcon('event stop')
  await rcon(`event setrank ${B1} 0`)
  await rcon(`event setrank ${B2} 0`)
  await sleep(1500)
  assertEq((await eventStatus()).active, 'none', 'initial state: no event')
  assert(!eventBar(b1), 'initial state: no event boss bar')

  try {
    await testMarketDay(b1, b2)
    await testOtherEvents(b1, b2)
    await testRank(b1, b2)
    await testRankFromSales(b1)
    b1 = await testLotto(b1, b2)
    if (LOTTO_RESTART) b2 = await testLottoRestart(b2)
  } finally {
    await restoreEventsConfig()
    for (const b of [b1, b2]) if (b.currentWindow) { try { b.closeWindow(b.currentWindow) } catch {} }
    await rcon('event stop')
    await rcon('smpeco pricemod clear')
    await rcon(`event setrank ${B1} 0`)
    await rcon(`event setrank ${B2} 0`)
    await rcon(`clear ${B1}`)
  }
}, { timeoutMs: LOTTO_RESTART ? 900000 : 420000 })

async function testMarketDay(b1, b2) {
  assert(near(await sellMult('minecraft:cobblestone', B1), 1.0), 'cobblestone without event: sellMult 1.0')
  b1.titles.length = 0
  const chat = waitForMessage(b1, /Market Day!.*Mining & Stone/, 8000).then(() => true, () => false)
  const start = await rcon('event start marketday mining 3')
  assert(/Market Day started/.test(start), `Admin-Start per RCON (${start.trim()})`)
  assert(await chat, 'chat announcement "Market Day!"')
  const title = await until(() => b1.titles.find((t) => t.kind === 'title' && /MARKET DAY/.test(t.text)))
  assert(!!title, 'title "MARKET DAY!" at the start')
  assert(b1.titles.some((t) => t.kind === 'subtitle' && /Mining & Stone/.test(t.text) && /1\.5/.test(t.text)), 'subtitle names category and x1.5')
  const bar = await until(() => eventBar(b1))
  assert(!!bar, `event bossbar visible (${bar?.title})`)
  assertEq(bar?.color, 'blue', 'market day boss bar blue (not red or green like breaking news)')
  assert(/Mining & Stone/.test(bar?.title ?? '') && /\d\d:\d\d/.test(bar?.title ?? ''), 'boss bar with category and countdown')
  assert(!!(await until(() => eventBar(b2))), 'boss bar also for the second player')
  const t1 = bar.title
  const h1 = bar.health
  await sleep(2300)
  const later = eventBar(b1)
  assert(later && (later.title !== t1 || later.health < h1), `countdown running (${t1} to ${later?.title})`)

  const st = await eventStatus()
  assertEq([st.active, st.param, st.bar], ['marketday', 'mining', true], 'event status: market day mining active')
  assert(st.remaining > 150 && st.remaining <= 180, `about 3 min left (${st.remaining}s)`)
  assert(near(await sellMult('minecraft:cobblestone', B1), 1.5), 'cobblestone: sellMult 1.5 through Hooks/PriceGuard')
  assert(near(await sellMult('minecraft:oak_log', B1), 1.0), 'oak log (other category): sellMult 1.0')
  assert(near(await sellMult('minecraft:diamond', B1), 1.0), 'diamond (no shop item): never market day')

  let win = await expectWindow(b1, () => b1.chat('/event'))
  assert(/Server Events/.test(windowTitle(win)), `hub opened ("${windowTitle(win)}")`)
  assert(/Market Day/.test(itemLabel(win.slots[HUB_EVENT])), `hub shows Market Day (${itemLabel(win.slots[HUB_EVENT])})`)
  assert(itemLore(win.slots[HUB_EVENT]).some((l) => /Ends in/.test(l)), 'hub lore with time left')
  assert(/Trader Rank/.test(itemLabel(win.slots[HUB_RANK])), 'hub shows Trader Rank')
  assert(/Lottery/.test(itemLabel(win.slots[HUB_LOTTO])), 'hub shows Lottery')
  win = await expectWindow(b1, () => clickSlot(b1, HUB_EVENT))
  assert(/Market Day: Mining & Stone/.test(windowTitle(win)), `market day items opened ("${windowTitle(win)}")`)
  const iron = findSlot(win, 'cobblestone')
  assert(iron >= 0, 'cobblestone in the list')
  const lore = iron >= 0 ? itemLore(win.slots[iron]) : []
  assert(lore.some((l) => /Sells now for/.test(l)) && lore.some((l) => /\+50% Market Day/.test(l)), `lore with price and +50% (${lore.join(' | ')})`)
  win = await expectWindow(b1, () => clickSlot(b1, 45))
  assert(/Server Events/.test(windowTitle(win)), 'back to the hub')
  b1.closeWindow(win)
  await sleep(300)
  assertEq(b1.inventory.items().map((i) => i.name), [], 'no GUI items in the inventory')

  const ended = waitForMessage(b1, /Market Day has been stopped/, 6000).then(() => true, () => false)
  await rcon('event stop')
  assert(await ended, 'Chat: Market Day has been stopped')
  assert(await until(() => !eventBar(b1)), 'boss bar removed after stop')
  assert(near(await sellMult('minecraft:cobblestone', B1), 1.0), 'after stop: sellMult 1.0 again')
}

async function testOtherEvents(b1, b2) {
  await rcon('event start goldrush miner 2')
  let st = await eventStatus()
  assertEq([st.active, st.param], ['goldrush', 'miner'], 'gold rush miner active')
  assert(near(st.jobMult, 2.0), `job multiplier through Hooks 2.0 (${st.jobMult})`)
  const gbar = await until(() => { const b = eventBar(b1); return b && /GOLD RUSH/.test(b.title) ? b : null })
  assertEq(gbar?.color, 'yellow', 'gold rush boss bar yellow')

  const replaced = waitForMessage(b1, /Gold Rush has been stopped/, 6000).then(() => true, () => false)
  await rcon('event start luckyhour 2')
  assert(await replaced, 'new event replaces the old one (end announced)')
  st = await eventStatus()
  assertEq(st.active, 'luckyhour', 'lucky hour active')
  assert(near(st.chatMult, 2.0) && st.lucky, `chat multiplier 2.0 + switch events.lucky_hour (${st.chatMult}, ${st.lucky})`)
  assert(near(st.jobMult, 1.0), 'gold rush factor gone')
  const lbar = await until(() => { const b = eventBar(b1); return b && /LUCKY HOUR/.test(b.title) ? b : null })
  assertEq(lbar?.color, 'purple', 'lucky hour boss bar purple')
  assertEq([...b1.bars.values()].filter((b) => /MARKET DAY|GOLD RUSH|LUCKY HOUR/.test(b.title)).length, 1, 'exactly one event boss bar')

  b2.chat('/event stop')
  await sleep(800)
  assertEq((await eventStatus()).active, 'luckyhour', 'non-admin cannot run /event stop')
  const info = waitForMessage(b2, /Lucky Hour.*left/, 5000).then(() => true, () => false)
  b2.chat('/event info')
  assert(await info, '/event info shows the running event')

  await rcon('event stop')
  st = await eventStatus()
  assert(st.active === 'none' && near(st.chatMult, 1.0) && !st.lucky, 'all neutral after stop')
  const bad = await rcon('event start marketday nonexistent 2')
  assert(/Unknown category/.test(bad), `unknown category refused (${bad.trim()})`)
  assertEq((await eventStatus()).active, 'none', 'no event after a failed start')
}

async function testRank(b1, b2) {
  const set = await rcon(`event setrank ${B1} 60000`)
  assert(/rank=merchant/.test(set), `revenue $60,000 → Merchant (${set.trim()})`)
  const msg = waitForMessage(b1, /Your Trader Rank: Merchant \(\+5% sell bonus\)/, 5000).then(() => true, () => false)
  b1.chat('/rank')
  assert(await msg, '/rank shows Merchant +5%')
  assert(near(await sellMult('minecraft:diamond', B1), 1.05), 'diamond: sellMult 1.05 for wholesaler')
  assert(near(await sellMult('minecraft:diamond', B2), 1.0), 'diamond: sellMult 1.0 for apprentice')
  const other = waitForMessage(b2, /eventsBot1's Trader Rank: Merchant/, 5000).then(() => true, () => false)
  b2.chat(`/rank ${B1}`)
  assert(await other, '/rank <player> shows another rank')
  const rankList = await rcon('rank list')
  assert(/Merchant from \$/.test(rankList), `/rank list shows all ranks (${rankList.trim().slice(0, 80)})`)

  let win = await expectWindow(b1, () => b1.chat('/event'))
  assert(/Merchant/.test(itemLabel(win.slots[HUB_RANK])), `Hub: rank Merchant (${itemLabel(win.slots[HUB_RANK])})`)
  assert(itemLore(win.slots[HUB_RANK]).some((l) => /Magnate/.test(l)), 'hub: next rank Magnate')
  win = await expectWindow(b1, () => clickSlot(b1, HUB_RANK))
  assert(/Trader Ranks/.test(windowTitle(win)), 'rank overview opened')
  const mine = findSlot(win, (it) => itemLore(it).some((l) => /Your rank/.test(l)))
  assert(mine >= 0 && /Merchant/.test(itemLabel(win.slots[mine])), 'own rank marked')
  assertEq([11, 12, 13, 14, 15].map((s) => itemLabel(win.slots[s])), ['Apprentice', 'Trader', 'Merchant', 'Magnate', 'Tycoon'], '5 ranks in order')
  win = await expectWindow(b1, () => clickSlot(b1, 18))
  assert(/Server Events/.test(windowTitle(win)), 'back to the hub')
  b1.closeWindow(win)
  await sleep(300)

  await rcon(`event setrank ${B1} 0`)
  assert(near(await sellMult('minecraft:diamond', B1), 1.0), 'Reset → Apprentice ×1.0')
}

const K = 4 // TraderRank.NET_CREDIT_FACTOR
const WHEAT = 'minecraft:wheat'
const COBBLE = 'minecraft:cobblestone'
const STONE = 'minecraft:stone'
const DIAMOND = 'minecraft:diamond'
const BONE = 'minecraft:bone'
const toCents = (s) => Math.round(Number(String(s).replace(/[$,]/g, '')) * 100)
const msg = (bot, re, ms = 8000) => waitForMessage(bot, re, ms).then((m) => m, () => null)
/** Documented deduction per piece (TraderRank.deduction): a shop item counts at most K x (pivot + 1 - price). */
function deduction(price, pivot) {
  if (pivot < 0 || price <= 0) return 0
  if (price > pivot) return price
  return Math.max(0, price - K * (pivot + 1 - price))
}

async function rankStatus(name) {
  const out = await rcon(`event status ${name}`)
  const m = out.match(/player=(\S+) rank=(\S+) sales=(\d+) rankMult=([\d.]+)/)
  if (!m) throw new Error(`event status ${name} unreadable: ${out}`)
  return { rank: m[2], sales: Number(m[3]), mult: Number(m[4]) }
}

async function priceOf(item, name) {
  const out = await rcon(`smpeco price ${item} ${name}`)
  const m = out.match(/sell=(-?\d+) .*sellMult=([\d.]+) .*pivot=(-?\d+)/)
  if (!m) throw new Error(`smpeco price ${item}: ${out}`)
  return { sell: Number(m[1]), sellMult: Number(m[2]), pivot: Number(m[3]) }
}

function invCount(bot, name) {
  let n = 0
  for (let i = 9; i <= 44; i++) {
    const it = bot.inventory.slots[i]
    if (it && it.name === name) n += it.count
  }
  return n
}

async function closeAll(bot) {
  if (bot.currentWindow) { bot.closeWindow(bot.currentWindow); await sleep(400) }
}

async function resetInv(bot) {
  await closeAll(bot)
  await rcon(`gamemode survival ${bot.username}`)
  await rcon(`clear ${bot.username}`)
  await until(() => bot.inventory.items().length === 0, 3000)
  bot.setQuickBarSlot(0)
  await sleep(200)
}

async function give(bot, item, count, check) {
  await rcon(`give ${bot.username} ${item} ${count}`)
  if (check) assert(!!(await until(check, 3000)), `${count}× ${item} in inventory`)
}

async function openSellGui(bot) {
  const win = await expectWindow(bot, () => cmd(bot, '/sell'), 8000)
  await sleep(300)
  assert(/Sell/.test(windowTitle(win)), `sell GUI opened ("${windowTitle(win)}")`)
  return win
}

async function testRankFromSales(b1) {
  await rcon('event stop')
  await rcon('smpeco pricemod clear')
  await rcon(`event setrank ${B1} 0`)
  await resetInv(b1)
  assertEq((await rankStatus(B1)).sales, 0, 'rank from sales: initial state without turnover')
  // Unrelated shop item at the pivot (like a hype or market day) must not push down the rating of other sales.
  // An estimate across all shop items, instead of the reported lines, would rate clearly less here.
  await rcon(`smpeco pricemod ${BONE} 10 10`)
  const pb = await priceOf(BONE, B1)
  assert(pb.pivot > 0 && pb.sell === pb.pivot && deduction(pb.sell, pb.pivot) > 0, `bone at the pivot (sell=${pb.sell}, pivot=${pb.pivot})`)

  const pd = await priceOf(DIAMOND, B1)
  assert(pd.pivot === -1 && pd.sell > 0, `diamond: no shop item, price ${pd.sell}`)
  await give(b1, DIAMOND, 64, () => invCount(b1, 'diamond') === 64)
  let sold = msg(b1, /Sold 64 items for \$([\d,]+\.\d\d)/)
  await cmd(b1, '/sell hand')
  let m = await sold
  assert(!!m && toCents(m[1]) === 64 * pd.sell, `/sell hand: 64 diamonds for ${64 * pd.sell} cents (${m?.[0]})`)
  assertEq((await rankStatus(B1)).sales, 64 * pd.sell, '/sell hand: rank turnover = proceeds')

  await resetInv(b1)
  await give(b1, WHEAT, 32)
  await give(b1, COBBLE, 32, () => invCount(b1, 'wheat') === 32 && invCount(b1, 'cobblestone') === 32)
  let before = (await rankStatus(B1)).sales
  const preview = msg(b1, /Sell all sellable items \(64\) for/)
  await cmd(b1, '/sell all')
  assert(!!(await preview), '/sell all: preview 64 items')
  assertEq((await rankStatus(B1)).sales, before, '/sell all: preview counts no turnover')
  sold = msg(b1, /Sold 64 items for \$([\d,]+\.\d\d)/)
  await cmd(b1, '/sell all')
  m = await sold
  assert(!!m, `/sell all: confirmed, 64 items sold (${m?.[0]})`)
  assertEq((await rankStatus(B1)).sales - before, m ? toCents(m[1]) : -1, '/sell all: rank turnover = proceeds')

  await resetInv(b1)
  await give(b1, WHEAT, 64)
  await give(b1, COBBLE, 64, () => invCount(b1, 'wheat') === 64 && invCount(b1, 'cobblestone') === 64)
  const pw = await priceOf(WHEAT, B1)
  const pc = await priceOf(COBBLE, B1)
  assert(pc.pivot > 0 && deduction(pc.sell, pc.pivot) === 0, `cobblestone at normal price without deduction (${pc.sell} at pivot ${pc.pivot})`)
  before = (await rankStatus(B1)).sales
  let win = await openSellGui(b1)
  await shiftClick(b1, hotbar(win, 0))
  await shiftClick(b1, hotbar(win, 1))
  await sleep(300)
  sold = msg(b1, /Sold 128 items for \$([\d,]+\.\d\d)/)
  b1.closeWindow(win)
  m = await sold
  const paidEsc = 64 * pw.sell + 64 * pc.sell
  assert(!!m && toCents(m[1]) === paidEsc, `sell GUI (ESC): 128 items for ${paidEsc} cents (${m?.[0]})`)
  assertEq((await rankStatus(B1)).sales - before, paidEsc, 'sell GUI (ESC): rank turnover = proceeds at normal prices')

  await resetInv(b1)
  await rcon(`smpeco pricemod ${STONE} 3 10`)
  const ps = await priceOf(STONE, B1)
  assert(ps.pivot > 0 && ps.sell === ps.pivot, `stone capped at the pivot (sell=${ps.sell}, pivot=${ps.pivot})`)
  const stoneNet = ps.sell - deduction(ps.sell, ps.pivot)
  assert(stoneNet < ps.sell, `stone at the pivot: counts only ${stoneNet} of ${ps.sell} cents per piece`)
  await give(b1, WHEAT, 32)
  await give(b1, STONE, 32, () => invCount(b1, 'wheat') === 32 && invCount(b1, 'stone') === 32)
  before = (await rankStatus(B1)).sales
  const home = b1.entity.position.clone()
  win = await openSellGui(b1)
  await shiftClick(b1, hotbar(win, 0))
  await shiftClick(b1, hotbar(win, 1))
  await sleep(300)
  sold = msg(b1, /Sold 64 items for \$([\d,]+\.\d\d)/, 10000)
  await rcon(`execute in minecraft:the_nether run tp ${B1} 0 120 0`)
  m = await sold
  const paidDim = 32 * pw.sell + 32 * ps.sell
  const netDim = 32 * pw.sell + 32 * stoneNet
  assert(!!m && toCents(m[1]) === paidDim, `dimension change: 64 items for ${paidDim} cents (${m?.[0]})`)
  assertEq((await rankStatus(B1)).sales - before, netDim, `dimension change: rank revenue exactly net (${netDim} of ${paidDim} cents)`)
  await rcon(`execute in minecraft:overworld run tp ${B1} ${home.x.toFixed(2)} ${home.y.toFixed(2)} ${home.z.toFixed(2)}`)
  await sleep(1500)

  await resetInv(b1)
  await give(b1, STONE, 64, () => invCount(b1, 'stone') === 64)
  before = (await rankStatus(B1)).sales
  win = await expectWindow(b1, () => cmd(b1, '/shop'))
  const cat = findSlot(win, /Building Blocks/)
  assert(cat >= 0, 'shop: category Building Blocks')
  win = await expectWindow(b1, () => clickSlot(b1, cat))
  const stoneSlot = findSlot(win, 'stone', { to: 45 })
  assert(stoneSlot >= 0, 'Shop: stone in Building Blocks')
  const dlg = await expectWindow(b1, () => clickSlot(b1, stoneSlot, 1, 0))
  assert(/Sell/.test(windowTitle(dlg)), `shop: sell dialog through right click (${windowTitle(dlg)})`)
  sold = msg(b1, /Sold 64× .* for \$([\d,]+\.\d\d)/)
  await clickSlot(b1, 49)
  m = await sold
  assert(!!m && toCents(m[1]) === 64 * ps.sell, `shop: 64 stone for ${64 * ps.sell} cents (${m?.[0]})`)
  const netShop = 64 * K * (ps.pivot + 1 - ps.sell)
  assertEq(64 * stoneNet, netShop, 'at the pivot the net value is pieces x K x (pivot + 1 - price)')
  assertEq((await rankStatus(B1)).sales - before, netShop, `shop right click: rank revenue net (${netShop} of ${64 * ps.sell} cents)`)
  await closeAll(b1)
  await rcon('smpeco pricemod clear')

  const list = await rcon('rank list')
  const hm = list.match(/Trader from \$([\d,]+\.\d\d) – \+([\d.]+)%/)
  assert(!!hm, `rank list readable (${list.trim().slice(0, 160)})`)
  if (!hm) return
  const threshold = toCents(hm[1])
  const bonus = Number(hm[2])
  await resetInv(b1)
  await rcon(`event setrank ${B1} ${((threshold - 1) / 100).toFixed(2)}`)
  let st = await rankStatus(B1)
  assertEq([st.rank, st.sales], ['apprentice', threshold - 1], 'rank turnover 1 cent below trader')
  await give(b1, DIAMOND, 64, () => invCount(b1, 'diamond') === 64)
  b1.titles.length = 0
  const up = msg(b1, new RegExp(`New Trader Rank: Trader \\(\\+${bonus}% sell bonus\\)`))
  sold = msg(b1, /Sold 64 items for \$([\d,]+\.\d\d)/)
  await cmd(b1, '/sell hand')
  m = await sold
  assert(!!m && toCents(m[1]) === 64 * pd.sell, `promotion sale: 64 diamonds still at the apprentice price (${m?.[0]})`)
  assert(!!(await up), `Chat: New Trader Rank: Trader (+${bonus}% sell bonus)`)
  assert(!!(await until(() => b1.titles.some((t) => t.kind === 'title' && /RANK UP/.test(t.text)))), 'title "RANK UP!"')
  assert(!!(await until(() => b1.titles.some((t) => t.kind === 'subtitle' && /Trader/.test(t.text) && t.text.includes(`+${bonus}%`)))),
    `subtitle: trader +${bonus}% on sales`)
  st = await rankStatus(B1)
  assertEq(st.rank, 'trader', 'event status: rank trader')
  assertEq(st.sales, threshold - 1 + 64 * pd.sell, 'event status: rank turnover with the promotion sale')
  assert(near(st.mult, 1 + bonus / 100), `event status: rankMult ${1 + bonus / 100}`)

  const pd2 = await priceOf(DIAMOND, B1)
  assert(near(pd2.sellMult, 1 + bonus / 100), `smpeco price: diamond sellMult ${pd2.sellMult} = 1 + ${bonus} %`)
  assert(pd2.sell > pd.sell, `trader price higher (${pd.sell} to ${pd2.sell})`)
  await give(b1, DIAMOND, 64, () => invCount(b1, 'diamond') === 64)
  sold = msg(b1, new RegExp(`Sold 64 items for \\$([\\d,]+\\.\\d\\d).*\\+${bonus}% Trader Rank`))
  await cmd(b1, '/sell hand')
  m = await sold
  assert(!!m && toCents(m[1]) === 64 * pd2.sell, `next /sell hand pays the trader price (${64 * pd2.sell} instead of ${64 * pd.sell} cents) and names +${bonus}% trader rank`)
  assertEq((await rankStatus(B1)).sales, threshold - 1 + 64 * pd.sell + 64 * pd2.sell, 'rank turnover counts the higher proceeds')

  await resetInv(b1)
  await rcon(`event setrank ${B1} 0`)
}

async function ensureTreasury(cents) {
  const { balance } = await treasuryFlows()
  if (balance >= cents) return 0
  const dollars = Math.ceil((cents - balance) / 100) + 1000
  const out = await rcon(`eco treasury deposit ${dollars}`)
  assert(/deposited/i.test(out), `treasury topped up (+$${dollars}): ${out.trim().slice(0, 100)}`)
  return dollars * 100
}

/**
 * Withdraws all treasury money, for the "treasury cannot cover the prize" case. Background entries can change
 * the balance between reading and withdrawing, hence up to 3 tries. Returns the withdrawn cents.
 */
async function drainTreasury() {
  let taken = 0
  for (let attempt = 0; attempt < 3; attempt++) {
    const { balance } = await treasuryFlows()
    if (balance <= 0) break
    const out = await rcon(`eco treasury withdraw ${(balance / 100).toFixed(2)}`)
    if (/withdrew/i.test(out)) taken += balance
  }
  const { balance } = await treasuryFlows()
  assert(balance < 100000, `treasury emptied (withdrew ${money(taken)}, rest ${money(balance)})`)
  return taken
}

// numbers nobody holds right now, for winning numbers surely not sold
function unsoldNumbers(size, taken, count) {
  const set = new Set(taken)
  const out = []
  for (let n = size; n >= 1 && out.length < count; n--) if (!set.has(n)) out.push(n)
  return out
}

/**
 * Makes the treasury cover the current draw (jackpot + extra prizes + pending prizes + reserve, plus a $1,000
 * margin for background entries); ticket sales pause otherwise. Waits until pending prizes are paid.
 */
async function ensureCovered() {
  let st = await lottoStatus()
  await ensureTreasury(st.need + 100000)
  st = await lottoStatus()
  assert(st.covered, `treasury covers draw #${st.draw} (need ${money(st.need)})`)
  if (st.owed > 0) {
    const paid = await until(async () => (await lottoStatus()).owed === 0, 15000, 500)
    assert(paid, 'pending prizes of earlier runs paid')
  }
  return st
}

async function testLotto(b1, b2) {
  let st = await lottoStatus()
  assert(st.ready, `lottery switched and ready (${st.raw.trim()})`)
  assertEq([st.size, st.base, st.prizes], [1000, 7200000, [1000000, 500000, 100000]], '1000 tickets, base jackpot $72,000, extra prizes $10k/$5k/$1k')
  assertEq([st.reserve, st.max, st.rollover], [2500000, 0, false], 'defaults: reserve $25,000, no jackpot limit, no rollover without tickets')
  assertEq(st.pot, 0, 'old lottery pot empty (moved to the treasury)')
  assertEq(st.need, st.jackpot + 1600000 + st.owed + st.reserve, 'coverage = jackpot + extra prizes + pending prizes + reserve')
  // forced winning numbers only on test servers: refused by default (an earlier aborted run may have left them
  // on)
  if (st.forcedDraws) await setEventsConfig({ lottoForcedDraws: false })
  const noForce = await rcon('lottery draw 1 2 3 4')
  assert(/test servers only/.test(noForce), `without lottoForcedDraws: forced numbers refused (${noForce.trim()})`)
  assertEq((await lottoStatus()).draw, st.draw, 'no draw')
  await setEventsConfig({ lottoForcedDraws: true })
  assertEq((await lottoStatus()).forcedDraws, true, 'Test-Server: lottoForcedDraws an (events.json + /smpeco reload)')
  st = await ensureCovered()
  // initial state: no open tickets (otherwise draw once; before the date only with "confirm")
  if (st.sold > 0) { await rcon('lottery draw confirm'); st = await ensureCovered() }
  assertEq(st.sold, 0, 'no open tickets')
  // an earlier run may have driven the jackpot up, which is fine, since the treasury gets topped up to match
  const base = st.base

  const refused = await rcon('lottery draw')
  assert(/No tickets sold/.test(refused), `draw without tickets refused (${refused.trim()})`)
  let after = await lottoStatus()
  assertEq([after.draw, after.jackpot], [st.draw, st.jackpot], 'without tickets: no draw, jackpot unchanged')

  await setMoney(B2, 1000)
  const oneB2 = msg(b2, /bought 1 ticket\b/)
  await cmd(b2, '/lottery buy 1')
  assert(!!(await oneB2), 'B2 buys 1 ticket')
  const b2first = (await lottoStatus(B2)).numbers
  const free4 = unsoldNumbers(1000, b2first, 4)
  // next draw's sale start only when the treasury also covers the grown jackpot
  await ensureTreasury((await lottoStatus()).need + base + 100000)
  const numbersMsg = msg(b2, /Lottery draw #(\d+) » Winning numbers: (#\d+ \| #\d+ \| #\d+ \| #\d+)/)
  const adminMsg = msg(b2, /ADMIN TEST DRAW » the winning numbers were set by an admin/)
  const rollMsg = msg(b2, /Jackpot not won – rolls over to (\$[\d,]+\.\d\d)!/)
  const noLuck0 = msg(b2, /No luck this time – next draw/)
  const saleMsg = msg(b2, /Tickets for lottery draw #(\d+) are on sale! Jackpot: (\$[\d,]+\.\d\d)/)
  b2.titles.length = 0
  const roll = await rcon(`lottery draw ${free4.join(' ')}`)
  assert(/jackpotWon=false/.test(roll) && /sold=1 /.test(roll) && /forced=true/.test(roll), `draw with 1 ticket: jackpot not won (${roll.trim()})`)
  after = await lottoStatus()
  assertEq(after.draw, st.draw + 1, 'draw number raised')
  assertEq(after.jackpot, st.jackpot + base, `jackpot grows by $72,000 (${money(st.jackpot)} to ${money(after.jackpot)})`)
  let m = await numbersMsg
  assert(!!m && Number(m[1]) === st.draw && new Set(nums(m[2])).size === 4, `chat: 4 distinct winning numbers (${m?.[0]})`)
  assert(!!(await adminMsg), 'chat to all: "ADMIN TEST DRAW – the winning numbers were set by an admin"')
  assertEq(after.last?.numbers, m ? nums(m[2]) : null, 'status: same winning numbers')
  assertEq(after.last?.winners, ['-', '-', '-', '-'], 'status: no winner (winning numbers not sold)')
  assertEq(after.last?.forced, true, 'status: forced numbers noted')
  m = await rollMsg
  assert(!!m && toCents(m[1]) === after.jackpot, `Chat: "Jackpot not won – rolls over to ${money(after.jackpot)}!" (${m?.[0]})`)
  assert(!!(await noLuck0), 'B2 (participant without a win): "No luck this time"')
  m = await saleMsg
  assert(!!m && Number(m[1]) === after.draw && toCents(m[2]) === after.jackpot, `chat: sale start with jackpot (${m?.[0]})`)
  assert(!!(await until(() => b2.titles.some((t) => t.kind === 'subtitle' && /Jackpot \$[\d,.]+ – tickets on sale!/.test(t.text)))),
    'title at sale start with jackpot amount')

  await setMoney(B1, 10000)
  await setMoney(B2, 10000)

  await testLottoDenied(b1)
  await testLottoPaused(b1)

  await ensureCovered()
  const flows0 = await treasuryFlows()
  let win = await expectWindow(b1, () => b1.chat('/lottery'))
  assert(/Lottery/.test(windowTitle(win)), `lottery GUI opened ("${windowTitle(win)}")`)
  assert(new RegExp(`JACKPOT: ${moneyRe(after.jackpot)}`).test(itemLabel(win.slots[LOTTO_JACKPOT])), `jackpot big on top (${itemLabel(win.slots[LOTTO_JACKPOT])})`)
  assert(/Sold: 0\/1000/.test(itemLabel(win.slots[LOTTO_SOLD])), `sold 0/1000 (${itemLabel(win.slots[LOTTO_SOLD])})`)
  const prizeLore = itemLore(win.slots[LOTTO_PRIZES]).join(' | ')
  assert(/\$10,000\.00/.test(prizeLore) && /\$5,000\.00/.test(prizeLore) && /\$1,000\.00/.test(prizeLore), `prizes in the lore (${prizeLore})`)
  assert(/Buy 5 tickets/.test(itemLabel(win.slots[LOTTO_BUY_5])), `Button "Buy 5 tickets" (${itemLabel(win.slots[LOTTO_BUY_5])})`)
  assert(/Buy 10 tickets/.test(itemLabel(win.slots[LOTTO_BUY_10])), `Button "Buy 10 tickets" (${itemLabel(win.slots[LOTTO_BUY_10])})`)
  assert(/Max: 50 tickets/.test(itemLabel(win.slots[LOTTO_BUY_MAX])), `Button "Max: 50 tickets" (${itemLabel(win.slots[LOTTO_BUY_MAX])})`)
  assert(itemLore(win.slots[LOTTO_BUY_5]).some((l) => /\$500\.00/.test(l)), 'cost $500 in the lore')
  const rules = itemLore(win.slots[LOTTO_RULES])
  assert(rules.some((l) => /playing alone doesn't win automatically/.test(l)), 'rules: alone does not win automatically')
  assert(rules.some((l) => /No tickets sold\? No draw/.test(l)), 'rules: no draw without tickets')
  const bought = msg(b1, /You bought 5 tickets \(−\$500\.00\): ((?:#\d+ ?)+)\./)
  await clickSlot(b1, LOTTO_BUY_5)
  m = await bought
  assert(!!m, `purchase message with 5 numbers (${m?.[0]})`)
  const first5 = m ? nums(m[1]) : []
  assert(first5.length === 5 && new Set(first5).size === 5 && first5.every((n) => n >= 1 && n <= 1000), `5 distinct numbers 1-1000 (${first5.join(' ')})`)
  await sleep(300)
  assert(/Your tickets: 5\/50/.test(itemLabel(b1.currentWindow?.slots[LOTTO_MINE])), `GUI updated: 5 tickets (${itemLabel(b1.currentWindow?.slots[LOTTO_MINE])})`)
  const mineLore = itemLore(b1.currentWindow?.slots[LOTTO_MINE]).join(' ')
  assert(first5.every((n) => mineLore.includes(`#${n}`)), `GUI shows own numbers (${mineLore})`)
  assert(/Sold: 5\/1000/.test(itemLabel(b1.currentWindow?.slots[LOTTO_SOLD])), 'GUI: 5/1000 sold')
  assert(itemLore(b1.currentWindow?.slots[LOTTO_CHANCE]).some((l) => /Jackpot: 0\.50%/.test(l)), `chance per prize 0.50% (${itemLore(b1.currentWindow?.slots[LOTTO_CHANCE]).join(' | ')})`)
  // debounce: two clicks in a row on the same buy button buy only once
  await b1.clickWindow(LOTTO_BUY_1, 0, 0)
  await b1.clickWindow(LOTTO_BUY_1, 0, 0)
  await sleep(600)
  let s1 = await lottoStatus(B1)
  assertEq([s1.playerTickets, s1.balance], [6, 940000], 'B1: 6 tickets, $9,400 (fast double click buys only 1 ticket)')
  assert(first5.every((n) => s1.numbers.includes(n)), 'status: numbers from the purchase message stored')
  const flows1 = await treasuryFlows()
  assertEq(treasuryDelta(flows0, flows1, R_TICKETS).income, 60000, 'treasury: +$600 tickets (reason events.lotto_tickets)')
  b1.closeWindow(b1.currentWindow)
  await sleep(300)

  win = await expectWindow(b2, () => b2.chat('/lottery'))
  const one = msg(b2, /bought 1 ticket\b/)
  await clickSlot(b2, LOTTO_BUY_1)
  assert(!!(await one), 'B2 buys 1 ticket')
  await sleep(400)
  assert(/Max: 49 tickets/.test(itemLabel(b2.currentWindow?.slots[LOTTO_BUY_MAX])), `max button shows 49 (${itemLabel(b2.currentWindow?.slots[LOTTO_BUY_MAX])})`)
  const max = msg(b2, /bought 49 tickets/)
  await clickSlot(b2, LOTTO_BUY_MAX)
  assert(!!(await max), 'B2 buys the maximum')
  await sleep(400)
  assert(/Maximum reached/.test(itemLabel(b2.currentWindow?.slots[LOTTO_BUY_1])), 'buttons show "Maximum reached"')
  b2.closeWindow(b2.currentWindow)
  await sleep(300)
  const maxMsg = msg(b2, /maximum of 50 tickets/)
  await cmd(b2, '/lottery buy 1')
  assert(!!(await maxMsg), '/lottery buy above the maximum refused')
  const s2 = await lottoStatus(B2)
  assertEq([s2.playerTickets, s2.balance], [50, 500000], 'B2: 50 Lose, $5,000')
  assert(new Set([...s1.numbers, ...s2.numbers]).size === 56, 'all 56 numbers distinct (none sold twice)')
  const tix = msg(b2, /Your tickets for draw #(\d+) \(50\/50\)/)
  const chance = msg(b2, /Chance per prize: 5\.0%.*any prize: (\d+\.\d)%/)
  await cmd(b2, '/lottery tickets')
  assert(!!(await tix), '/lottery tickets: 50/50')
  m = await chance
  assert(!!m && Math.abs(Number(m[1]) - 18.6) < 0.11, `/lottery tickets: 5.0% per prize, about 18.6% any prize (${m?.[0]})`)

  await setMoney(B1, 100)
  const poor = msg(b1, /Not enough money/)
  await cmd(b1, '/lottery buy 1000')
  assert(!!(await poor), 'not enough money → refused')
  s1 = await lottoStatus(B1)
  assertEq([s1.playerTickets, s1.balance], [6, 10000], 'B1 unchanged (6 tickets, $100)')

  st = await ensureCovered()
  assertEq([st.sold, st.players], [56, 2], '56 tickets of 2 players')
  const jackpot = st.jackpot
  const [free] = unsoldNumbers(1000, [...s1.numbers, ...s2.numbers], 1)
  const forced = [s1.numbers[0], s2.numbers[0], free, s1.numbers[1]]
  const before = { [B1]: s1.balance, [B2]: s2.balance }
  const fl0 = await treasuryFlows()
  const jpMsg = msg(b1, new RegExp(`Lottery win! JACKPOT! Your ticket #${forced[0]} won the jackpot of ${moneyRe(jackpot)} in draw #${st.draw}!`))
  const smallMsg1 = msg(b1, new RegExp(`Lottery win! Your ticket #${forced[3]} won the \\$1,000\\.00 prize in draw #${st.draw}!`))
  const winMsg2 = msg(b2, new RegExp(`Lottery win! Your ticket #${forced[1]} won the \\$10,000\\.00 prize in draw #${st.draw}!`))
  const hitMsg = msg(b2, new RegExp(`${B1} hit the JACKPOT of ${moneyRe(jackpot)}!`))
  const noLuck = msg(b2, /No luck this time/, 4000)
  b1.titles.length = 0
  b2.titles.length = 0
  const draw = await rcon(`lottery draw ${forced.join(' ')}`)
  const dm = draw.match(/numbers=(\S+) jackpotWon=(true|false) jackpot=(\d+) nextJackpot=(\d+) sold=(\d+) forced=(true|false) winners=(\S+)/)
  assert(!!dm, `draw with forced numbers (${draw.trim()})`)
  if (dm) {
    assertEq(dm[1], forced.join(','), 'winning numbers as forced (jackpot first)')
    assertEq([dm[2], Number(dm[3]), Number(dm[4]), Number(dm[5]), dm[6]], ['true', jackpot, base, 56, 'true'], 'jackpot won, next one $72,000 again')
    assertEq(dm[7], `0:${B1}:${jackpot}:0,1:${B2}:1000000:0,2:-:0:0,3:${B1}:100000:0`, 'winners and payouts per rank (nothing pending)')
  }
  assert(!!(await jpMsg), 'B1: private jackpot message')
  assert(!!(await smallMsg1), 'B1: private message for the $1,000 prize')
  assert(!!(await winMsg2), 'B2: private message for the $10,000 prize')
  assert(!!(await hitMsg), 'chat to all: B1 hit the JACKPOT')
  assert(!(await noLuck), 'B2 won, so no "No luck"')
  assertEq((await lottoStatus(B1)).balance, before[B1] + jackpot + 100000, 'B1 gets jackpot + $1,000')
  assertEq((await lottoStatus(B2)).balance, before[B2] + 1000000, 'B2 gets $10,000')
  const fl1 = await treasuryFlows()
  assertEq(treasuryDelta(fl0, fl1, R_PRIZES).expense, jackpot + 1100000, 'treasury pays exactly the prizes (reason events.lotto_prizes)')
  assert(fl1.balance >= st.reserve, `treasury stays above the reserve (${money(fl1.balance)})`)
  assert(await until(() => b1.titles.some((t) => t.kind === 'title' && /JACKPOT!/.test(t.text))), 'B1: title "JACKPOT!"')
  assert(await until(() => b2.titles.some((t) => t.kind === 'title' && /LOTTERY WIN!/.test(t.text))), 'B2: title "LOTTERY WIN!"')
  st = await lottoStatus()
  assertEq([st.sold, st.jackpot], [0, base], 'tickets deleted, jackpot $72,000 again')
  assertEq(st.last?.winners, [B1, B2, '-', B1], 'status: winners per rank')

  const hist = msg(b2, new RegExp(`#${st.last?.id} .*JACKPOT WON.*admin test draw`))
  const histLine = msg(b2, new RegExp(`Jackpot ${moneyRe(jackpot)} » #${forced[0]} » ${B1}`))
  await cmd(b2, '/lottery history')
  assert(!!(await hist), '/lottery history: last draw, jackpot won, marked admin test draw')
  assert(!!(await histLine), '/lottery history: jackpot number and winner')
  win = await expectWindow(b2, () => cmd(b2, '/lottery'))
  const hl = itemLore(win.slots[LOTTO_HISTORY]).join(' | ')
  assert(new RegExp(`Draw #${st.last?.id}`).test(itemLabel(win.slots[LOTTO_HISTORY])), `GUI history: draw #${st.last?.id} (${itemLabel(win.slots[LOTTO_HISTORY])})`)
  assert(forced.every((n) => hl.includes(`#${n}`)) && hl.includes(B1) && hl.includes(B2) && /not sold/.test(hl), `GUI history: all 4 numbers and winners (${hl})`)
  assert(/Admin test draw/.test(hl), 'GUI history: "Admin test draw" noted')
  assert(new RegExp(`JACKPOT: ${moneyRe(base)}`).test(itemLabel(win.slots[LOTTO_JACKPOT])), 'GUI: new jackpot $72,000')
  b2.closeWindow(win)
  await sleep(300)

  await ensureCovered()
  await setMoney(B1, 1000)
  await setMoney(B2, 1000)
  await cmd(b1, '/lottery buy 1')
  await cmd(b2, '/lottery buy 2')
  await sleep(500)
  s1 = await lottoStatus(B1)
  let s2b = await lottoStatus(B2)
  assertEq([s1.playerTickets, s2b.playerTickets], [1, 2], 'new draw: B1 1 ticket, B2 2 tickets')
  const free3 = unsoldNumbers(1000, [...s1.numbers, ...s2b.numbers], 3)
  const drained = await drainTreasury()
  const balB2 = s2b.balance
  const rollMsg2 = msg(b1, new RegExp(`Jackpot not won – rolls over to ${moneyRe(2 * base)}!`))
  const noLuck1 = msg(b1, /No luck this time/)
  const owedMsg = msg(b2, new RegExp(`Lottery win! Your ticket #${s2b.numbers[0]} won the \\$1,000\\.00 prize.*Treasury`))
  const pausedMsg = msg(b1, /Tickets for draw #\d+ go on sale as soon as the Treasury can cover the prizes/)
  const draw2 = await rcon(`lottery draw ${[...free3, s2b.numbers[0]].join(' ')}`)
  const dm2 = draw2.match(/jackpotWon=(true|false) jackpot=(\d+) nextJackpot=(\d+) .*winners=0:[^,]*,1:[^,]*,2:[^,]*,3:([^:]+):(\d+):(\d+)/)
  assert(!!dm2 && dm2[1] === 'false' && Number(dm2[3]) === 2 * base && dm2[4] === B2, `draw: jackpot not sold, $1,000 to B2 (${draw2.trim()})`)
  const paidNow = dm2 ? Number(dm2[5]) : -1
  const owedNow = dm2 ? Number(dm2[6]) : -1
  assertEq(paidNow + owedNow, 100000, `paid + pending = $1,000 (${paidNow} + ${owedNow})`)
  assert(owedNow > 0, `treasury below the reserve: prize stays pending (${owedNow} cents)`)
  assert(!!(await rollMsg2), `Chat: "Jackpot not won – rolls over to ${money(2 * base)}!"`)
  assert(!!(await noLuck1), 'B1 (participant without a win): "No luck this time"')
  assert(!!(await owedMsg), 'B2: prize message with a hint about the treasury')
  assert(!!(await pausedMsg), 'chat: no sale start while the treasury does not cover the next draw')
  st = await lottoStatus(B2)
  assertEq(st.jackpot, 2 * base, `jackpot now ${money(2 * base)}`)
  assert(st.owedTo > 0 && st.owedTo <= owedNow, `status: pending prize for B2 (${st.owedTo})`)
  assertEq(st.covered, false, 'status: sales paused (treasury does not cover the next draw)')
  win = await expectWindow(b2, () => cmd(b2, '/lottery'))
  assert(itemLore(win.slots[LOTTO_MINE]).some((l) => /Outstanding winnings/.test(l)), 'GUI: "Outstanding winnings"')
  b2.closeWindow(win)
  await sleep(300)
  const settled = msg(b2, /the rest of your \$1,000\.00 prize from draw #\d+ has now been paid out in full/, 15000)
  if (drained > 0) await rcon(`eco treasury deposit ${(drained / 100).toFixed(2)}`)
  await ensureTreasury(st.reserve + 1000000)
  assert(!!(await settled), 'B2: back payment reported once the treasury is above the reserve again')
  st = await lottoStatus(B2)
  assertEq([st.owedTo, st.balance], [0, balB2 + 100000], 'B2: nothing pending, exactly $1,000 received')

  st = await ensureCovered()
  if (st.sold > 0) { await rcon('lottery draw confirm'); st = await ensureCovered() }
  await setMoney(B1, 1000)
  const one2 = msg(b1, /bought 1 ticket\b/)
  await cmd(b1, '/lottery buy 1')
  assert(!!(await one2), 'B1 buys 1 ticket')
  const n1 = (await lottoStatus(B1)).numbers[0]
  b1.quit()
  await sleep(1500)
  const [j1, j2, j4] = unsoldNumbers(1000, [n1], 3)
  const offline = await rcon(`lottery draw ${j1} ${j2} ${n1} ${j4}`)
  assert(new RegExp(`2:${B1}:500000:0`).test(offline), `B1 wins $5,000 offline (${offline.trim()})`)
  const b1again = track(await connectBot(B1))
  const pending = await waitForMessage(b1again, new RegExp(`Lottery win! Your ticket #${n1} won the \\$5,000\\.00 prize`), 10000, { includePast: true }).then(() => true, () => false)
  assert(pending, 'pending message for the offline winner on join')
  assertEq((await lottoStatus(B1)).balance, 1000 * 100 - 10000 + 500000, 'offline prize paid ($990 + $5,000)')

  await testLottoEarlyDraw(b2)
  return b1again
}

/**
 * Early admin draw: with sold tickets only with "confirm"; it replaces the planned draw (the next is one date
 * later, not twice on one day) and is marked as an admin draw in chat.
 */
async function testLottoEarlyDraw(b2) {
  let st = await ensureCovered()
  if (st.sold > 0) { await rcon('lottery draw confirm'); st = await ensureCovered() }
  await setMoney(B2, 1000)
  const one = msg(b2, /bought 1 ticket\b/)
  await cmd(b2, '/lottery buy 1')
  assert(!!(await one), 'B2 buys 1 ticket')
  st = await lottoStatus()
  assert(st.sold === 1 && st.next > 60, `draw #${st.draw} planned in ${st.next} s, 1 ticket sold`)
  const refused = await rcon('lottery draw')
  assert(/use \/lottery draw confirm/.test(refused), `early draw with tickets without "confirm" refused (${refused.trim()})`)
  assertEq((await lottoStatus()).draw, st.draw, 'no draw without confirmation')
  const adminLine = msg(b2, /\(drawn by an admin\)/)
  const out = await rcon('lottery draw confirm')
  const dm = out.match(/draw=(\d+) .*forced=false .*nextDraw=(\d+)/)
  assert(!!dm && Number(dm[1]) === st.draw, `early draw with "confirm" (${out.trim()})`)
  const nextDraw = dm ? Number(dm[2]) : -1
  // date used up: next draw one date (24 h, 23/25 h at the clock change) after the old one
  assert(nextDraw >= st.next + 23 * 3600 - 5 && nextDraw <= st.next + 25 * 3600 + 5,
    `next draw one date later (${st.next} s to ${nextDraw} s), not again on the same day`)
  assert(!!(await adminLine), 'Chat: "(drawn by an admin)"')
  const after = await lottoStatus()
  assertEq([after.draw, after.sold], [st.draw + 1, 0], 'next draw open')
}

async function testLottoPaused(b1) {
  const before = await lottoStatus(B1)
  const drained = await drainTreasury()
  try {
    const st = await lottoStatus()
    assertEq(st.covered, false, `treasury empty: sales paused (need ${money(st.need)})`)
    const win = await expectWindow(b1, () => cmd(b1, '/lottery'))
    assert(/Sales paused/.test(itemLabel(win.slots[LOTTO_BUY_1])), `GUI: buy buttons "Sales paused" (${itemLabel(win.slots[LOTTO_BUY_1])})`)
    assert(itemLore(win.slots[LOTTO_JACKPOT]).some((l) => /Ticket sales are paused/.test(l)), 'GUI: hint at the jackpot')
    await clickSlot(b1, LOTTO_BUY_1)
    b1.closeWindow(win)
    await sleep(300)
    const paused = msg(b1, /Ticket sales are paused/)
    await cmd(b1, '/lottery buy 1')
    assert(!!(await paused), '/lottery buy: "Ticket sales are paused"')
    const after = await lottoStatus(B1)
    assertEq([after.playerTickets, after.balance], [before.playerTickets, before.balance], 'paused: no ticket, no money charged')
  } finally {
    if (drained > 0) await rcon(`eco treasury deposit ${(drained / 100).toFixed(2)}`)
  }
  assertEq((await ensureCovered()).covered, true, 'treasury topped up: sales open again')
}

async function restartWith(name, kill) {
  const { restartServer } = await import('../tools/server-restart.mjs')
  await closeRcon()
  await restartServer({ kill })
  return track(await connectBot(name))
}

/**
 * Tickets survive a clean restart (numbers, jackpot, draw); a draw saved with save-all is not paid again after
 * kill -9 (same balance, next draw open, not due).
 */
async function testLottoRestart(b2) {
  let st = await ensureCovered()
  if (st.sold > 0) { await rcon('lottery draw confirm'); st = await ensureCovered() }
  await setMoney(B2, 1000)
  const bought = msg(b2, /You bought 3 tickets/)
  await cmd(b2, '/lottery buy 3')
  assert(!!(await bought), 'restart test: B2 buys 3 tickets')
  const pre = await lottoStatus(B2)
  await rcon('save-all')
  b2 = await restartWith(B2, false)
  const post = await lottoStatus(B2)
  assertEq([post.draw, post.jackpot, post.sold, post.numbers], [pre.draw, pre.jackpot, pre.sold, pre.numbers], 'after the restart: draw, jackpot and numbers unchanged')
  assertEq(post.balance, pre.balance, 'after the restart: balance unchanged')

  assertEq(post.forcedDraws, true, 'after the restart: lottoForcedDraws from events.json')
  await ensureCovered()
  const [j1, j3, j4] = unsoldNumbers(1000, post.numbers, 3)
  const out = await rcon(`lottery draw ${j1} ${post.numbers[0]} ${j3} ${j4}`)
  assert(new RegExp(`1:${B2}:1000000:0`).test(out), `B2 wins $10,000 (${out.trim()})`)
  const paid = await lottoStatus(B2)
  assertEq(paid.balance, pre.balance + 1000000, 'paid')
  await rcon('save-all')
  await sleep(500)
  b2 = await restartWith(B2, true)
  const again = await lottoStatus(B2)
  assertEq(again.balance, paid.balance, 'after kill -9: prize exactly once (not paid a second time)')
  assertEq([again.draw, again.sold, again.last?.id], [paid.draw, 0, pre.draw], 'after kill -9: next draw open, last in the history')
  assert(again.next > 0, `after kill -9: next draw not due at once (in ${again.next} s)`)
  await sleep(2500)
  assertEq((await lottoStatus(B2)).draw, again.draw, 'no catch-up draw after the restart')
  return b2
}

async function hubLottoLore(bot) {
  const win = await expectWindow(bot, () => bot.chat('/event'))
  const lore = itemLore(win.slots[HUB_LOTTO])
  bot.closeWindow(win)
  await sleep(200)
  return lore
}

// /event needs only smpeconomy.events; without smpeconomy.lottery there must still be no ticket purchase,
// neither through the overview's lottery icon nor from an open lottery GUI. LuckPerms works asynchronously, so
// wait for the effect instead of the RCON reply.
async function testLottoDenied(b1) {
  const before = await lottoStatus(B1)
  let win = await expectWindow(b1, () => b1.chat('/lottery'))
  assert(/Buy 1 ticket\b/.test(itemLabel(win.slots[LOTTO_BUY_1])), `lottery GUI ready to buy before the block (${itemLabel(win.slots[LOTTO_BUY_1])})`)
  const set = await rcon(`lp user ${B1} permission set ${NODE_LOTTO} false`)
  if (/Unknown|incomplete/i.test(set)) {
    console.log(`[E2E] INFO LuckPerms not available (${set.trim().slice(0, 80)}), block test skipped`)
    b1.closeWindow(b1.currentWindow)
    await sleep(300)
    return
  }
  try {
    // an open lottery GUI redraws its buy buttons locked (checked each second), and a click buys nothing
    const locked = await until(() => /Not unlocked/.test(itemLabel(b1.currentWindow?.slots[LOTTO_BUY_1])), 10000, 250)
    assert(locked, `blocked: open lottery GUI locks the buy buttons (${itemLabel(b1.currentWindow?.slots[LOTTO_BUY_1])})`)
    await clickSlot(b1, LOTTO_BUY_1)
    await clickSlot(b1, LOTTO_BUY_MAX)
    b1.closeWindow(b1.currentWindow)
    await sleep(300)

    const lore = await until(async () => {
      const l = await hubLottoLore(b1)
      return l.some((x) => /Not unlocked/.test(x)) ? l : null
    }, 8000, 300)
    assert(!!lore, 'blocked: /event shows the lottery as "Not unlocked"')
    assert(!!lore && !lore.some((x) => /Click/.test(x)), 'blocked: no click hint at the lottery icon')
    win = await expectWindow(b1, () => b1.chat('/event'))
    const opened = waitForWindow(b1, 1500).then(() => true, () => false)
    await clickSlot(b1, HUB_LOTTO)
    assert(!(await opened), 'blocked: click on the lottery in /event opens no lottery GUI')
    assert(/Server Events/.test(windowTitle(b1.currentWindow)), 'blocked: overview stays open')
    b1.closeWindow(b1.currentWindow)
    await sleep(300)

    b1.chat('/lottery buy 1')
    await sleep(800)
    const after = await lottoStatus(B1)
    assertEq([after.playerTickets, after.balance], [before.playerTickets, before.balance], 'blocked: no tickets bought, no money charged')
  } finally {
    if (b1.currentWindow) { try { b1.closeWindow(b1.currentWindow) } catch {} }
    await rcon(`lp user ${B1} permission unset ${NODE_LOTTO}`)
  }
  const back = await until(async () => (await hubLottoLore(b1)).some((x) => /Click: buy tickets/.test(x)), 8000, 300)
  assert(back, 'block lifted: lottery in /event clickable again')
}
