// Casino "scratch" (scratch cards): selftests via RCON, entry in the /casino hub, GUI (prize table, help, cards), a few
// Bronze cards scratched field by field, with "Scratch all", closed mid-card and left by disconnecting, help line, max bet
// and daily loss limit guards. Every card is checked against the result stored at purchase (smpeco casino peek), and
// money is conserved: balance = before − price + payout and the Treasury books exactly price/payout under "casino".
// With E2E_SERVER_DIR set (private server copy; skip with E2E_NO_RESTART=1) also a crash: card bought, save-all, kill -9,
// restart → the stored result is paid exactly once.
// Usage: E2E_PORT=… E2E_RCON_PORT=… E2E_SERVER_DIR=… node e2e/casino-scratch.mjs
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, treasuryFlows, closeWindow, dollars, kv,
} from './lib.mjs'

const BOT = 'BotScratchA'
const PRICE = 10000 // Bronze card, $100 in cents
const CELLS = [12, 13, 14, 21, 22, 23, 30, 31, 32]
const SLOT_PAYTABLE = 4
const SLOT_HELP = 10
const SLOT_STATUS = 16
const SLOT_ACTION = 25
const SLOT_BRONZE = 47
const SLOT_SILVER = 49
const SLOT_GOLD = 51
const HUB_SLOT = 23 // CasinoMenus.SLOT_SCRATCH
const MULT = { Copper: 1, Iron: 2, Gold: 3, Emerald: 5, Diamond: 10, 'Nether Star': 25 }
const CASINO_REASON = 'casino'

async function info(name = BOT) {
  const line = await rcon(`smpeco casino info ${name}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`smpeco casino info ${name}: ${line}`)
  const f = (await treasuryFlows()).flows[CASINO_REASON] ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

async function peek(name = BOT) {
  return kv(await rcon(`smpeco casino peek ${name}`))
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

async function waitSettled(name = BOT, timeoutMs = 8000) {
  const end = Date.now() + timeoutMs
  let v
  do {
    v = await info(name)
    if (v.open === 'none') return v
    await sleep(200)
  } while (Date.now() < end)
  return v
}

/** Prize of 9 revealed symbol names: multiplier of the symbol shown 3 times, 0 without a triple. */
function evaluate(names) {
  const count = {}
  for (const n of names) count[n] = (count[n] ?? 0) + 1
  const triples = Object.keys(count).filter((n) => count[n] >= 3)
  if (triples.length > 1) return -1
  return triples.length ? MULT[triples[0]] : 0
}

const ledger = { cards: 0, stakes: 0, payouts: 0 }

const SETTLED_CLOSED = /Scratch Cards: (?:Won \$[\d,.]+ \(×[\d.,]+, bet \$[\d,.]+\)|Lost \(bet \$[\d,.]+\)) \(window closed\)/
function checkCard(label, before, after, payout) {
  assertEq(after.balance, before.balance - PRICE + payout, `${label}: balance = before − $100 + payout (${dollars(payout)})`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [PRICE, payout],
    `${label}: Treasury books price/payout under "casino"`)
  assertEq(after.open, 'none', `${label}: no open round`)
  assertEq(after.rounds, before.rounds + 1, `${label}: statistics +1 round`)
  ledger.cards++
  ledger.stakes += PRICE
  ledger.payouts += payout
}

async function checkBought(label, before) {
  const p = await peek()
  assertEq([p.open, p.state, p.stake], ['scratch', 'decided', PRICE], `${label}: card stored with a decided result`)
  assert([0, ...Object.values(MULT)].includes(p.payout / PRICE), `${label}: payout ${dollars(p.payout)} is a table prize`)
  assertEq(p.reserved, p.payout, `${label}: Treasury reserves exactly the decided payout`)
  const mid = await info()
  assertEq(mid.balance, before.balance - PRICE, `${label}: $100 taken, prize not paid before scratching`)
  assertEq(mid.casinoOut, before.casinoOut, `${label}: no payout booked yet`)
  return p.payout
}

function revealedNames(bot) {
  return CELLS.map((s) => itemLabel(bot.currentWindow?.slots?.[s]))
}

runE2E(async () => {
  const st = await rcon('smpeco selftest casino-scratch')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 200)})`)
  if (sm) {
    assert(Number(sm[1]) >= 8, `scratch selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `scratch selftests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  }

  let bot = await connectBot(BOT, { timeoutMs: 180000 })
  await sleep(1500)
  await rcon(`smpeco casino settle ${BOT}`)
  await rcon(`smpeco casino resetlimit ${BOT}`)
  await rcon(`eco set ${BOT} 5000`)
  await sleep(300)
  const start = await info()
  assertEq(start.balance, 500000, 'start balance $5,000')
  assert(start.maxBet >= PRICE && start.cover >= 25 * PRICE,
    `Treasury allows Bronze (maxBet=${start.maxBet}, cover=${start.cover}, Treasury=${start.treasury})`)

  try {
    bot = await guiTests(bot)
    await helpTest(bot)
    await guardTests(bot)
    bot = await disconnectTest(bot)
    if (process.env.E2E_SERVER_DIR && process.env.E2E_NO_RESTART !== '1') bot = await crashTest(bot)
    else console.log('[E2E] crash test skipped (needs E2E_SERVER_DIR of a private server, E2E_NO_RESTART!=1)')
  } finally {
    try { await closeWindow(globalThis.scratchBot ?? bot) } catch {}
    await rcon(`smpeco casino settle ${BOT}`)
    await rcon(`smpeco casino resetlimit ${BOT}`)
  }

  const end = await info()
  assertEq(end.balance - start.balance, ledger.payouts - ledger.stakes, `all ${ledger.cards} cards: balance change = payouts − prices`)
  assertEq([end.casinoIn - start.casinoIn, end.casinoOut - start.casinoOut], [ledger.stakes, ledger.payouts],
    'all cards: Treasury "casino" income = prices, expense = payouts')
  assertEq(end.rounds - start.rounds, ledger.cards, 'all cards: statistics count each card once')
  console.log(`[E2E] cards=${ledger.cards} prices=${dollars(ledger.stakes)} payouts=${dollars(ledger.payouts)}`)
}, { timeoutMs: 360000 })

async function guiTests(bot) {
  let win = await expectWindow(bot, () => bot.chat('/casino'))
  assert(/Casino/.test(windowTitle(win)), `casino hub open (${windowTitle(win)})`)
  assertEq(itemLabel(win.slots[HUB_SLOT]), 'Scratch Cards', 'hub has the Scratch Cards entry')
  assert(itemLore(win.slots[HUB_SLOT]).some((l) => /Available: .*Bronze/.test(l)), `hub shows Bronze as available (${itemLore(win.slots[HUB_SLOT]).join(' | ')})`)
  win = await expectWindow(bot, () => clickSlot(bot, HUB_SLOT))
  assert(/Scratch Cards/.test(windowTitle(win)), `scratch GUI opens from the hub (${windowTitle(win)})`)
  assert(/Prize table/.test(itemLabel(win.slots[SLOT_PAYTABLE])), 'prize table shown')
  const table = itemLore(win.slots[SLOT_PAYTABLE])
  assert(table.some((l) => /3× Nether Star → ×25 \(\$2,500\.00\)/.test(l)), `prize table: Nether Star ×25 = $2,500 (${table.join(' | ')})`)
  assert(table.some((l) => /Payout rate 95\.5 %/.test(l)), 'prize table: payout rate 95.5 %')
  assertEq(itemLabel(win.slots[SLOT_HELP]), 'How to play', 'help item')
  assert(/Bronze Card/.test(itemLabel(win.slots[SLOT_BRONZE])) && /Silver Card/.test(itemLabel(win.slots[SLOT_SILVER]))
    && /Gold Card/.test(itemLabel(win.slots[SLOT_GOLD])), 'three cards in the bottom row')
  const silverLocked = itemLore(win.slots[SLOT_SILVER]).some((l) => /Locked|limit|money/i.test(l))
  const maxBet = (await info()).maxBet
  if (maxBet < 100000) assert(silverLocked, `Silver shown as locked while max bet is ${dollars(maxBet)}`)
  assertEq(itemLabel(win.slots[CELLS[0]]), 'Scratch field', 'empty card before buying')

  let before = await info()
  await clickSlot(bot, SLOT_BRONZE)
  assert(await waitSlot(bot, CELLS[0], /^Scratch!$/), 'Bronze bought: 9 hidden fields')
  let payout = await checkBought('card 1', before)
  for (let i = 0; i < CELLS.length; i++) {
    await clickSlot(bot, CELLS[i])
    const item = await waitSlot(bot, CELLS[i], new RegExp(`^(${Object.keys(MULT).join('|')})$`), 3000)
    assert(!!item, `card 1: field ${i + 1} scratched (${itemLabel(bot.currentWindow?.slots?.[CELLS[i]])})`)
    if (i < CELLS.length - 1) assertEq((await info()).open, 'scratch:decided', `card 1: still open after ${i + 1} fields`)
  }
  let after = await waitSettled()
  await sleep(300)
  const names = revealedNames(bot)
  assertEq(evaluate(names) * PRICE, payout, `card 1: the 9 symbols (${names.join(', ')}) show the stored result`)
  checkCard('card 1', before, after, payout)
  const status = itemLabel(bot.currentWindow.slots[SLOT_STATUS])
  const expectStatus = payout > PRICE ? /YOU WIN/ : payout === PRICE ? /Money back/ : /No prize/
  assert(expectStatus.test(status), `card 1: status "${status}"`)

  before = await info()
  assert(/Buy another/.test(itemLabel(bot.currentWindow.slots[SLOT_ACTION])), '"Buy another" after the card')
  await clickSlot(bot, SLOT_ACTION)
  assert(await waitSlot(bot, SLOT_ACTION, /Scratch all/), 'card 2 bought, "Scratch all" offered')
  payout = await checkBought('card 2', before)
  await sleep(350) // same slot again: > Gui.SAME_SLOT_DEBOUNCE_MS
  await clickSlot(bot, SLOT_ACTION)
  after = await waitSettled()
  await sleep(300)
  const names2 = revealedNames(bot)
  assert(names2.every((n) => n in MULT), `card 2: all fields revealed (${names2.join(', ')})`)
  assertEq(evaluate(names2) * PRICE, payout, 'card 2: symbols show the stored result')
  checkCard('card 2', before, after, payout)

  // card 3: two fields, then close the window; paid out at the fixed result, once
  before = await info()
  await sleep(350)
  await clickSlot(bot, SLOT_ACTION)
  assert(await waitSlot(bot, CELLS[4], /^Scratch!$/), 'card 3 bought')
  payout = await checkBought('card 3', before)
  await clickSlot(bot, CELLS[4])
  await clickSlot(bot, CELLS[8])
  const closedMsg = waitForMessage(bot, SETTLED_CLOSED, 6000).then(() => true, () => false)
  await closeWindow(bot)
  after = await waitSettled()
  checkCard('card 3 (closed after 2 fields)', before, after, payout)
  assert(await closedMsg, 'card 3: result message names the closed window')
  await sleep(1000)
  assertEq((await info()).balance, after.balance, 'card 3: not paid twice')
  return bot
}

async function helpTest(bot) {
  const help = waitForMessage(bot, /3 matching symbols win/, 5000).then(() => true, () => false)
  bot.chat('/scratch help')
  assert(await help, '/scratch help explains the game')
}

async function guardTests(bot) {
  // max bet (≤ 2 % of the Treasury): Gold costs $10,000
  const i = await info()
  if (i.maxBet < 1000000) {
    const err = waitForMessage(bot, /Maximum bet: \$[\d,.]+ \(at most [\d.]+ ?% of the Treasury\)/, 6000).then(() => true, () => false)
    await expectWindow(bot, () => bot.chat('/scratch gold'))
    assert(await err, `Gold above the max bet (${dollars(i.maxBet)}) rejected`)
    const i2 = await info()
    assertEq([i2.balance, i2.open, i2.casinoIn], [i.balance, 'none', i.casinoIn], 'rejected Gold: nothing taken, no round')
    await closeWindow(bot)
  } else {
    console.log(`[E2E] max-bet check skipped: Treasury allows Gold (maxBet=${i.maxBet})`)
  }
  // daily loss limit (shared with all casino games): today's counters reset, own limit $0 → no card
  await rcon(`smpeco casino resetlimit ${BOT}`)
  const set = waitForMessage(bot, /Daily loss limit is now \$0\.00/, 5000).then(() => true, () => false)
  bot.chat('/casino limit 0')
  assert(await set, 'own daily loss limit set to $0')
  const lim = await info()
  assertEq([lim.limit, lim.room], [0, 0], 'limit $0 active, nothing left today')
  const limited = waitForMessage(bot, /Daily loss limit reached \(\$0\.00\)\. Come back tomorrow\./, 6000).then(() => true, () => false)
  await expectWindow(bot, () => bot.chat('/scratch bronze'))
  assert(await limited, 'card above the daily loss limit rejected')
  const after = await info()
  assertEq([after.balance, after.open, after.casinoIn], [lim.balance, 'none', lim.casinoIn], 'rejected by the limit: nothing taken')
  const lore = itemLore(bot.currentWindow?.slots?.[SLOT_BRONZE])
  assert(lore.some((l) => /Daily loss limit reached/.test(l)), `GUI shows the limit on the card (${lore.join(' | ')})`)
  await closeWindow(bot)
  await rcon(`smpeco casino resetlimit ${BOT}`)
  assert((await info()).limit > 0, 'admin reset lifts the limit again')
}

async function disconnectTest(bot) {
  const before = await info()
  await expectWindow(bot, () => bot.chat('/scratch bronze'))
  assert(await waitSlot(bot, CELLS[0], /^Scratch!$/), '/scratch bronze buys a card right away')
  const payout = await checkBought('card 4', before)
  await clickSlot(bot, CELLS[0])
  bot.quit()
  await sleep(2500)
  const after = await waitSettled()
  checkCard('card 4 (disconnect mid-card)', before, after, payout)
  const back = await connectBot(BOT, { timeoutMs: 180000 })
  globalThis.scratchBot = back
  const msg = await waitForMessage(back, SETTLED_CLOSED, 10000, { includePast: true }).then(() => true, () => false)
  assert(msg, 'card 4: result delivered after reconnecting')
  await sleep(1500)
  assertEq((await info()).balance, after.balance, 'card 4: no second payout after joining')
  return back
}

async function crashTest(bot) {
  const { restartServer } = await import('../tools/server-restart.mjs')
  const before = await info()
  await expectWindow(bot, () => bot.chat('/scratch bronze'))
  assert(await waitSlot(bot, CELLS[0], /^Scratch!$/), 'card 5 bought')
  const payout = await checkBought('card 5', before)
  await rcon('save-all')
  await sleep(1500)
  console.log('[E2E] kill -9 with an unscratched card …')
  await restartServer({ kill: true })
  const after = await waitSettled(BOT, 20000)
  checkCard('card 5 (server crash after purchase)', before, after, payout)
  assert(/casino settle none/.test(await rcon(`smpeco casino settle ${BOT}`)), 'card 5: nothing left to settle')
  const back = await connectBot(BOT, { timeoutMs: 180000 })
  globalThis.scratchBot = back
  await sleep(2500)
  assertEq((await info()).balance, after.balance, 'card 5: paid exactly once after the restart')
  return back
}
