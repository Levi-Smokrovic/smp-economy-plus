// Casino game "Crash": selftests, /casino menu entry, /crash window and boss bar, rounds with auto cash-out,
// manual cash-out (button and graph), crashed rounds, disconnect mid-flight (cashed out once at the last shown
// multiplier), guards, one casino-wide daily loss limit. After every round balance = before - bet + payout and
// the treasury books exactly bet and payout under reason "casino", never checked on the raw balance, which NPC
// orders change.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, stripColors, treasuryFlows, textOf, cents, closeWindow, dollars, kv,
} from './lib.mjs'

const BOT = 'BotCrashA'
const BET = 1000 // $10 in cents
const SLOT_HUB = 20 // CasinoMenus.SLOT_CRASH
const SLOT_HELP = 0
const SLOT_MULT = 4
const SLOT_AUTO = 8
const SLOT_ACTION = 49
const GRAPH_CELL = 9 // top-left cell of the graph: clicking anywhere on the graph cashes out

const total = { bets: 0, payouts: 0 }

async function info(name) {
  const line = await rcon(`smpeco crash info ${name}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`smpeco crash info ${name}: ${line}`)
  const f = (await treasuryFlows()).flows.casino ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

async function peek(name) {
  return kv(await rcon(`smpeco crash peek ${name}`))
}

const hundredths = (s) => Math.round(parseFloat(s) * 100)

function resultPromise(bot, timeoutMs = 60000) {
  const re = /Crash: (?:Cashed out at ×(\d+\.\d\d) → (\$[\d,]+\.\d\d)|Crashed at ×(\d+\.\d\d)|Round cancelled, bet (\$[\d,]+\.\d\d) refunded)/
  return waitForMessage(bot, re, timeoutMs).then((m) => ({
    cashed: !!m[1],
    crashed: !!m[3],
    refund: !!m[4],
    at: m[1] ? hundredths(m[1]) : m[3] ? hundredths(m[3]) : 0,
    payout: m[2] ? cents(m[2]) : m[4] ? cents(m[4]) : 0,
    line: m[0],
  }))
}

function checkBalances(label, before, after, bet, payout) {
  assertEq(after.balance, before.balance - bet + payout, `${label}: balance = before − bet + payout`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [bet, payout],
    `${label}: treasury books bet/payout (reason casino)`)
  assertEq(after.open, 'none', `${label}: no open round`)
  total.bets += bet
  total.payouts += payout
}

function shownMultiplier(bot) {
  const label = itemLabel(bot.currentWindow?.slots?.[SLOT_MULT])
  const m = label.match(/^×(\d+\.\d\d)$/)
  return m ? hundredths(m[1]) : null
}

async function waitMultiplier(bot, want, timeoutMs = 30000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const m = shownMultiplier(bot)
    if (m !== null && m >= want) return m
    if (/CRASHED|Cashed out|cancelled/.test(itemLabel(bot.currentWindow?.slots?.[SLOT_MULT]))) return null
    await sleep(50)
  }
  return null
}

async function resetPlayer(name) {
  await rcon(`smpeco crash settle ${name}`)
  await rcon(`smpeco casino settle ${name}`)
  await rcon(`smpeco casino resetlimit ${name}`)
  await rcon(`smpeco crash reset ${name}`)
}

let bot
const bossTitles = []

runE2E(async () => {
  const st = await rcon('smpeco selftest casino-crash')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `self-test summary present (${st.slice(0, 200)})`)
  if (sm) {
    assert(Number(sm[1]) >= 14, `crash self-tests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `crash self-tests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  }

  bot = await connectBot(BOT, { setup: watchBossbar })
  await sleep(1500)
  await resetPlayer(BOT)
  await rcon(`eco set ${BOT} 5000`)
  await sleep(300)
  const start = await info(BOT)
  assertEq(start.balance, 500000, `start balance $5000 (${JSON.stringify(start)})`)
  assert(start.maxBet >= 2 * BET, `max bet allows $10 rounds (maxBet=${start.maxBet}, treasury=${start.treasury})`)
  assert(start.maxBet <= Math.floor(start.treasury * 0.02), 'max bet ≤ 2 % of the treasury')

  try {
    await hubTests()
    await autoRounds()
    await manualRounds()
    await crashRound()
    await sharedLimit()
    await guardTests()
    await disconnectTest()
  } finally {
    try { await closeWindow(bot) } catch {}
    await resetPlayer(BOT)
  }

  const end = await info(BOT)
  assertEq(end.balance, start.balance - total.bets + total.payouts, `whole run: balance = start − ${dollars(total.bets)} bets + ${dollars(total.payouts)} payouts`)
  assertEq([end.casinoIn - start.casinoIn, end.casinoOut - start.casinoOut], [total.bets, total.payouts], 'whole run: treasury ledger (casino) matches')
}, { timeoutMs: 420000 })

function watchBossbar(b) {
  const note = (bar) => { try { bossTitles.push(stripColors(textOf(bar?.title))) } catch {} }
  b.on('bossBarCreated', note)
  b.on('bossBarUpdated', note)
}

async function hubTests() {
  const win = await expectWindow(bot, () => bot.chat('/casino'))
  assert(/Casino/.test(windowTitle(win)), `casino menu open (${windowTitle(win)})`)
  assertEq(itemLabel(win.slots[SLOT_HUB]), 'Crash', 'crash entry in the casino menu')
  assert(itemLore(win.slots[SLOT_HUB]).some((l) => /\/crash/.test(l)), 'entry names /crash')
  const game = await expectWindow(bot, () => clickSlot(bot, SLOT_HUB))
  assert(/Crash/.test(windowTitle(game)), `crash window from the menu (${windowTitle(game)})`)
  assertEq(itemLabel(game.slots[SLOT_HELP]), 'How to play', 'help item')
  assert(itemLore(game.slots[SLOT_HELP]).some((l) => /House edge: 4 %/.test(l)), 'help names the house edge')
  assert(/LAUNCH/.test(itemLabel(game.slots[SLOT_ACTION])), `launch button (${itemLabel(game.slots[SLOT_ACTION])})`)
  assert(/Auto cash-out/.test(itemLabel(game.slots[SLOT_AUTO])), 'auto cash-out selector')
  const autoBefore = itemLabel(bot.currentWindow.slots[SLOT_AUTO])
  await clickSlot(bot, SLOT_AUTO)
  await sleep(200)
  assert(itemLabel(bot.currentWindow.slots[SLOT_AUTO]) !== autoBefore, `auto cash-out changes on click (${autoBefore} → ${itemLabel(bot.currentWindow.slots[SLOT_AUTO])})`)
  const helped = waitForMessage(bot, /crash <bet> \[auto\|off\]/, 5000).then(() => true, () => false)
  await closeWindow(bot)
  bot.chat('/crash help')
  assert(await helped, '/crash help explains the command')
}

async function autoRounds() {
  for (let i = 1; i <= 4; i++) {
    const before = await info(BOT)
    const result = resultPromise(bot)
    const win = await expectWindow(bot, () => bot.chat(`/crash ${dollars(BET)} 1.5`))
    if (i === 1) assert(/Crash/.test(windowTitle(win)), 'crash window opens with /crash <bet> <auto>')
    const p = await peek(BOT)
    const r = await result
    if (p.open !== 'none') {
      assertEq(p.state, 'playing', `auto #${i}: round stored as playing right after the start`)
      assertEq(p.auto, 150, `auto #${i}: auto cash-out stored`)
      assert(p.crash >= 100, `auto #${i}: crash point stored before the flight (×${dollars(p.crash)})`)
      if (p.crash >= 150) {
        assert(r.cashed && r.at === 150 && r.payout === 1500, `auto #${i}: crash ×${dollars(p.crash)} ≥ ×1.50 → paid $15 (${r.line})`)
      } else {
        assert(r.crashed && r.at === p.crash && r.payout === 0, `auto #${i}: crash ×${dollars(p.crash)} < ×1.50 → lost (${r.line})`)
      }
    } else {
      // already over when peeked (crash within the first ticks)
      assert(r.crashed ? r.payout === 0 : r.at === 150 && r.payout === 1500, `auto #${i}: round already over, consistent result (${r.line})`)
    }
    await sleep(300)
    const after = await info(BOT)
    checkBalances(`auto #${i} (${r.cashed ? 'won' : 'lost'})`, before, after, BET, r.payout)
    assertEq(after.rounds, before.rounds + 1, `auto #${i}: stats +1 round`)
  }
  assert(bossTitles.some((t) => /×\d+\.\d\d/.test(t)), `boss bar shows the live multiplier (${bossTitles.slice(-3).join(' | ')})`)
  assert(bossTitles.some((t) => /CRASHED|Cashed out/.test(t)), 'boss bar shows the result')
  await closeWindow(bot)
}

async function manualRounds() {
  const ways = [{ slot: SLOT_ACTION, name: 'button' }, { slot: GRAPH_CELL, name: 'graph' }]
  for (const way of ways) {
    let done = false
    for (let n = 0; n < 8 && !done; n++) {
      const before = await info(BOT)
      const result = resultPromise(bot)
      await expectWindow(bot, () => bot.chat(`/crash ${dollars(BET)} off`))
      const p = await peek(BOT)
      const seen = await waitMultiplier(bot, 110)
      if (seen !== null) {
        if (way.slot === SLOT_ACTION) assert(/CASH OUT/.test(itemLabel(bot.currentWindow.slots[SLOT_ACTION])), 'CASH OUT button while flying')
        await bot.clickWindow(way.slot, 0, 0)
      }
      const r = await result
      await sleep(300)
      const after = await info(BOT)
      checkBalances(`manual ${way.name} #${n} (${r.cashed ? 'cashed ×' + dollars(r.at) : 'crashed ×' + dollars(r.at)})`, before, after, BET, r.payout)
      if (r.cashed) {
        assert(p.open === 'none' || r.at <= p.crash, `manual ${way.name}: cashed at ×${dollars(r.at)} ≤ crash point ×${dollars(p.crash)}`)
        assert(seen !== null && r.at >= seen, `manual ${way.name}: paid at the server multiplier ×${dollars(r.at)} ≥ seen ×${dollars(seen ?? 0)}`)
        assertEq(r.payout, Math.floor(BET * r.at / 100), `manual ${way.name}: payout = bet × multiplier`)
        done = true
      } else if (p.open !== 'none') {
        assertEq(r.at, p.crash, `manual ${way.name}: crashed exactly at the stored crash point`)
      }
      await sleep(300)
    }
    assert(done, `manual cash-out via ${way.name} seen`)
  }
  await closeWindow(bot)
}

/** Auto ×5 until one round crashes: lost bet, crash at the stored crash point. */
async function crashRound() {
  let crashed = false
  for (let n = 0; n < 8 && !crashed; n++) {
    const before = await info(BOT)
    const result = resultPromise(bot)
    await expectWindow(bot, () => bot.chat(`/crash ${dollars(BET)} 5`))
    const p = await peek(BOT)
    const r = await result
    await sleep(300)
    const after = await info(BOT)
    checkBalances(`auto ×5 #${n} (${r.crashed ? 'crashed' : 'won'})`, before, after, BET, r.payout)
    if (r.crashed) {
      crashed = true
      if (p.open !== 'none') assertEq(r.at, p.crash, 'crash at the stored crash point')
      assert(/CRASHED/.test(itemLabel(bot.currentWindow?.slots?.[SLOT_MULT])), 'window shows CRASHED')
    } else {
      assertEq(r.payout, 5 * BET, 'auto ×5 reached → $50')
    }
  }
  assert(crashed, 'a crashed round was seen')
  await closeWindow(bot)
}

/** One daily loss limit for the whole casino: house games see crash losses, the casino reset resets crash too. */
async function sharedLimit() {
  const casinoInfo = async () => kv(await rcon(`smpeco casino info ${BOT}`))
  // the house games see today's crash result (the bot played only crash since the reset at the start)
  const x = await info(BOT)
  const c = await casinoInfo()
  assertEq([c.netToday, c.room], [x.netToday, x.room], `casino net loss/room include crash (casino ${c.netToday}/${c.room}, crash ${x.netToday}/${x.room})`)
  await rcon(`smpeco casino resetlimit ${BOT}`)
  assertEq((await info(BOT)).netToday, 0, '/smpeco casino resetlimit resets the crash day')
  // lose a crash round (auto x100 almost always crashes first), then personal limit = loss + $5
  let net = 0
  for (let n = 0; n < 6 && net <= 0; n++) {
    const before = await info(BOT)
    const result = resultPromise(bot)
    await expectWindow(bot, () => bot.chat(`/crash ${dollars(BET)} 100`))
    const r = await result
    await sleep(300)
    const after = await info(BOT)
    checkBalances(`shared-limit round #${n} (${r.crashed ? 'crashed' : 'won'})`, before, after, BET, r.payout)
    net = after.netToday
  }
  assert(net > 0, `a crash loss today (net ${dollars(net)})`)
  await closeWindow(bot)
  const set = waitForMessage(bot, /limit/i, 5000).then(() => true, () => false)
  bot.chat(`/casino limit ${dollars(net + 500)}`)
  await set
  const c2 = await casinoInfo()
  assertEq([c2.netToday, c2.room], [net, 500], `house games: net loss = crash loss, room $5 (${JSON.stringify(c2)})`)
  assertEq((await info(BOT)).room, 500, 'crash: room $5')
  // a $10 house-game round must be refused
  const before = await info(BOT)
  const refused = waitForMessage(bot, /Daily loss limit/, 5000).then(() => true, () => false)
  bot.chat(`/slots ${dollars(BET)}`)
  assert(await refused, '$10 slots spin refused: crash losses count against the house games')
  await sleep(300)
  const after = await info(BOT)
  assertEq(after.balance, before.balance, 'nothing taken when refused')
  await closeWindow(bot)
  await rcon(`smpeco casino resetlimit ${BOT}`)
}

async function guardTests() {
  const i = await info(BOT)
  const over = waitForMessage(bot, /Maximum bet/, 5000).then(() => true, () => false)
  bot.chat(`/crash ${dollars(i.maxBet + 100)} 2`)
  assert(await over, `bet above the max bet (${dollars(i.maxBet)}) refused`)
  await closeWindow(bot)
  const bad = waitForMessage(bot, /Invalid auto cash-out/, 5000).then(() => true, () => false)
  bot.chat(`/crash ${dollars(BET)} 0.5`)
  assert(await bad, 'auto cash-out ×0.50 refused')
  // daily loss limit (personal limit $5 < bet $10)
  await rcon(`smpeco casino resetlimit ${BOT}`)
  await rcon(`smpeco crash reset ${BOT}`)
  const limited = waitForMessage(bot, /limit/i, 5000).then(() => true, () => false)
  bot.chat('/casino limit 5')
  await limited
  const refused = waitForMessage(bot, /Daily loss limit reached/, 5000).then(() => true, () => false)
  const before = await info(BOT)
  bot.chat(`/crash ${dollars(BET)} 2`)
  assert(await refused, 'bet above the daily loss limit refused')
  await sleep(300)
  const after = await info(BOT)
  assertEq(after.balance, before.balance, 'nothing taken when refused')
  await closeWindow(bot)
  await rcon(`smpeco casino resetlimit ${BOT}`)
}

/** Leave the server mid-flight: the round is cashed out at the last shown multiplier, exactly once. */
async function disconnectTest() {
  for (let n = 0; n < 8; n++) {
    const before = await info(BOT)
    const result = resultPromise(bot).catch(() => null)
    await expectWindow(bot, () => bot.chat(`/crash ${dollars(BET)} off`))
    const p = await peek(BOT)
    if (p.open === 'none' || p.crash < 160) {
      // crash point too low for a clean mid-flight disconnect: let it crash and try again
      const r = await result
      await sleep(300)
      const after = await info(BOT)
      assert(r && r.crashed, `pre-disconnect round #${n}: crashed at ×${dollars(r?.at ?? 0)}`)
      checkBalances(`pre-disconnect round #${n}`, before, after, BET, r ? r.payout : after.casinoOut - before.casinoOut)
      continue
    }
    const seen = await waitMultiplier(bot, 125, 30000)
    assert(seen !== null, `flying at ×${dollars(seen ?? 0)} before leaving (crash ×${dollars(p.crash)})`)
    bot.quit()
    await sleep(2500)
    const after = await info(BOT)
    const payout = after.casinoOut - before.casinoOut
    checkBalances('disconnect mid-flight', before, after, BET, payout)
    assert(payout >= Math.floor(BET * seen / 100) && payout <= Math.floor(BET * p.crash / 100),
      `disconnect paid ×${dollars(payout * 100 / BET)} between seen ×${dollars(seen)} and crash ×${dollars(p.crash)}`)
    bot = await connectBot(BOT)
    const msg = await waitForMessage(bot, /Crash: Cashed out at ×\d+\.\d\d → \$[\d,.]+ .*window closed/, 10000, { includePast: true }).then(() => true, () => false)
    assert(msg, 'result delivered after reconnecting')
    await sleep(1500)
    const again = await info(BOT)
    assertEq(again.balance, after.balance, 'no second payout after joining')
    return
  }
  assert(false, 'no round with a crash point ≥ ×1.60 for the disconnect test')
}
