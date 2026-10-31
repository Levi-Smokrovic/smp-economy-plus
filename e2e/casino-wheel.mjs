// Casino game "Wheel of Fortune" (/wheel): selftests, /casino hub entry, GUI, spins by command and SPIN button,
// bet buttons, closing and disconnecting mid-spin, guards, help. For every spin the outcome is decided and
// stored while the wheel still turns (smpeco casino peek); afterwards balance = before - bet + payout and the
// treasury booked exactly that under "casino". E2E_WHEEL_RESTART=1 (private server copy only: E2E_SERVER_DIR,
// E2E_PORT, E2E_RCON_PORT) adds a clean stop and a kill -9 mid-spin: the saved round settles exactly once after
// the restart.
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { E2E_SERVER_DIR } from '../tools/server-restart.mjs'
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, stripColors, treasuryFlows, closeRcon, cents, dollars, kv,
} from './lib.mjs'

const B = 'wheelBot1'
const STAKE = 1000 // $10 in cents
const RING = [4, 5, 6, 7, 16, 25, 34, 43, 42, 41, 40, 39, 38, 37, 28, 19, 10, 1, 2, 3]
const TENTHS = [500, 0, 12, 20, 0, 15, 12, 50, 0, 12, 30, 0, 15, 12, 100, 0, 20, 12, 0, 0]
const HUB_SLOT = 22 // CasinoMenus.SLOT_WHEEL
const PAYTABLE = 11
const POINTER = 13
const HELP = 15
const CENTER = 22
const BAR = 45
const BET = BAR + 3
const SPIN = BAR + 4
const CLOSE = BAR + 8
const CASINO_REASON = 'casino'

let bot
let totalStaked = 0
let totalPaid = 0
/** Balance changes made by the test itself (eco set), not by the casino. */
let ecoAdjust = 0

async function info(name = B) {
  const line = await rcon(`smpeco casino info ${name}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`smpeco casino info ${name}: ${line}`)
  const f = (await treasuryFlows()).flows[CASINO_REASON] ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

async function peek(name = B) {
  return kv(await rcon(`smpeco casino peek ${name}`))
}

async function peekOpen(timeoutMs = 2500) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const p = await peek()
    if (p.open !== 'none' || Date.now() > end) return p
    await sleep(60)
  }
}

async function waitSettled(timeoutMs = 10000) {
  const end = Date.now() + timeoutMs
  for (;;) {
    const v = await info()
    if (v.open === 'none' || Date.now() > end) return v
    await sleep(150)
  }
}

function money(c) {
  const s = (Math.abs(c) / 100).toFixed(2).split('.')
  return (c < 0 ? '-$' : '$') + s[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + s[1]
}

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

function labelTenths(label) {
  const m = String(label).match(/^(\d+)(?:\.(\d))?×/)
  return m ? Number(m[1]) * 10 + Number(m[2] ?? 0) : NaN
}

function checkMoney(label, before, after, stake, payout) {
  assertEq(after.balance, before.balance - stake + payout, `${label}: balance = before − bet + payout`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [stake, payout],
    `${label}: Treasury booked bet/payout (reason casino)`)
  assertEq(after.open, 'none', `${label}: no open round`)
  assertEq(after.rounds, before.rounds + 1, `${label}: counted once`)
  assertEq([after.wagered - before.wagered, after.paid - before.paid], [stake, payout], `${label}: stats wagered/paid`)
  totalStaked += stake
  totalPaid += payout
}

async function checkDecided(label, before, stake) {
  const pk = await peekOpen()
  assertEq(pk.open, 'wheel', `${label}: wheel round open while spinning`)
  assertEq(pk.state, 'decided', `${label}: outcome decided before the wheel stops`)
  assertEq(pk.stake, stake, `${label}: bet stored`)
  const expected = Math.floor((stake * TENTHS[pk.card]) / 10)
  assertEq(pk.payout, expected, `${label}: payout = bet × ${TENTHS[pk.card] / 10} (segment ${pk.card})`)
  assertEq(pk.reserved, pk.payout, `${label}: reserves exactly its payout`)
  const mid = await info()
  assertEq(mid.balance, before.balance - stake, `${label}: bet collected, payout not yet`)
  return pk
}

async function closeWindow(b = bot) {
  if (b.currentWindow) {
    b.closeWindow(b.currentWindow)
    await sleep(400)
  }
}

async function spin(label, trigger, stake) {
  const before = await info()
  const seen = []
  const collect = (m) => seen.push(stripColors(m))
  bot.on('messagestr', collect)
  const t0 = Date.now()
  await trigger()
  const pk = await checkDecided(label, before, stake)
  const after = await waitSettled()
  const took = Date.now() - t0
  // ~3 s of animation (60 ticks; shorter only when the server catches up on lagged ticks)
  assert(took >= 1000 && took <= 7000, `${label}: settled when the wheel stopped (${took} ms)`)
  checkMoney(label, before, after, stake, pk.payout)
  await sleep(400)
  bot.off('messagestr', collect)
  // the wheel settled it itself, not the casino watchdog (5 s grace) or a close
  const foreign = seen.find((m) => /\((window closed|timed out|Admin)\)/i.test(m))
  assert(!foreign, `${label}: settled by the wheel, not by the watchdog${foreign ? ` (${foreign})` : ''}`)
  const resultLine = seen.find((m) => /Wheel of Fortune: (?:Won|Lost)/.test(m))
  assert(!!resultLine, `${label}: result line names the wheel (${resultLine})`)
  const w = bot.currentWindow
  assert(!!w && /Wheel of Fortune/.test(windowTitle(w)), `${label}: wheel stays open`)
  if (w) {
    assertEq(labelTenths(itemLabel(w.slots[RING[0]])), TENTHS[pk.card], `${label}: drawn segment under the pointer (${itemLabel(w.slots[RING[0]])})`)
    const center = itemLabel(w.slots[CENTER])
    assert(pk.payout > 0 ? cents(center) === pk.payout : /no win/.test(center), `${label}: result in the centre (${center})`)
    assert(/SPIN/.test(itemLabel(w.slots[SPIN])), `${label}: SPIN ready again`)
  }
  return pk
}

runE2E(async () => {
  const st = await rcon('smpeco selftest casino-wheel')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary (${st.slice(0, 200)})`)
  if (sm) assertEq(Number(sm[2]), 0, `wheel selftests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  const logFile = join(E2E_SERVER_DIR, 'logs', 'latest.log')
  try {
    const log = readFileSync(logFile, 'utf8')
    for (const name of ['rtp', 'animation', 'exactly-once', 'crash-recovery', 'guards', 'english', 'conservation']) {
      const lines = log.split('\n').filter((l) => l.includes(`casino-wheel/wheel-${name}:`))
      const last = lines[lines.length - 1] ?? ''
      assert(/\[SELFTEST\] PASS/.test(last), `selftest wheel-${name} PASS (${last.replace(/^.*\[SELFTEST\] /, '').slice(0, 160)})`)
    }
  } catch (err) {
    console.log(`[E2E] INFO ${logFile} not readable (${err.message}) – wheel selftests only covered by failed=0`)
  }

  bot = await connectBot(B)
  await sleep(1500)
  await rcon(`smpeco casino settle ${B}`)
  await rcon(`smpeco casino resetlimit ${B}`)
  await rcon(`eco set ${B} 5000`)
  await sleep(300)
  const i0 = await info()
  assertEq(i0.balance, 500000, `start balance $5000 (${JSON.stringify(i0)})`)
  const wheelMax0 = Math.min(i0.maxBet, Math.floor(i0.cover / 50), i0.room)
  assert(wheelMax0 >= 2 * STAKE, `wheel max bet allows $10 spins (max ${wheelMax0}, Treasury ${i0.treasury})`)

  try {
    await hubTests()
    await spinTests()
    await closeMidSpin()
    await disconnectMidSpin()
    await guardTests()
    await helpTest()
    if (process.env.E2E_WHEEL_RESTART === '1') await restartTests()
    else console.log('[E2E] INFO restart checks skipped (E2E_WHEEL_RESTART=1 against a private copy)')
    const iEnd = await info()
    assertEq([iEnd.casinoIn - i0.casinoIn, iEnd.casinoOut - i0.casinoOut], [totalStaked, totalPaid],
      `whole run: Treasury booked all bets (${money(totalStaked)}) and payouts (${money(totalPaid)})`)
    assertEq(iEnd.balance, i0.balance - totalStaked + totalPaid + ecoAdjust, 'whole run: bot balance = start − bets + payouts')
  } finally {
    try { await closeWindow() } catch {}
    await rcon(`smpeco casino settle ${B}`)
    await rcon(`smpeco casino resetlimit ${B}`)
  }
}, { timeoutMs: 600000 })

async function hubTests() {
  let win = await expectWindow(bot, () => bot.chat('/casino'))
  assert(/Casino/.test(windowTitle(win)), `casino hub open (${windowTitle(win)})`)
  assertEq(itemLabel(win.slots[HUB_SLOT]), 'Wheel of Fortune', 'hub: wheel entry')
  const lore = itemLore(win.slots[HUB_SLOT])
  assert(lore.some((l) => /\/wheel <bet>/.test(l)) && lore.some((l) => /Max bet: \$/.test(l)), `hub entry lore (${lore.join(' | ')})`)
  win = await expectWindow(bot, () => clickSlot(bot, HUB_SLOT))
  assert(/Wheel of Fortune/.test(windowTitle(win)), `wheel opened from the hub (${windowTitle(win)})`)
  const segs = RING.map((s) => labelTenths(itemLabel(win.slots[s])))
  assert(segs.every((t) => TENTHS.includes(t)), `ring of 20 segments (${segs.join(',')})`)
  // the ring shows the wheel in order (rotated): consecutive positions = consecutive segments backwards
  const start = TENTHS.findIndex((_, off) => RING.every((_, pos) => TENTHS[(((off - pos) % 20) + 20) % 20] === segs[pos]))
  assert(start >= 0, 'ring shows the real wheel layout')
  assert(/Pointer/.test(itemLabel(win.slots[POINTER])), 'pointer marker')
  const pay = itemLore(win.slots[PAYTABLE]).join(' | ')
  assert(/50× · 0\.2 %/.test(pay) && /0× · 55\.3 %/.test(pay) && /Return to player 96 %/.test(pay), `paytable with odds (${pay})`)
  assertEq(itemLabel(win.slots[HELP]), 'How to play', 'help item')
  assert(/^Bet: \$/.test(itemLabel(win.slots[BET])), `bet display (${itemLabel(win.slots[BET])})`)
  assert(/SPIN/.test(itemLabel(win.slots[SPIN])), 'SPIN button')
  const b0 = cents(itemLabel(win.slots[BET]))
  await clickSlot(bot, BAR + 5)
  await sleep(250)
  const b1 = cents(itemLabel(bot.currentWindow.slots[BET]))
  assertEq(b1, Math.min(b0 + 10000, wheelMaxNow(await info())), `+$100 (${money(b0)} → ${money(b1)})`)
  await clickSlot(bot, BAR + 1)
  await sleep(250)
  const b2 = cents(itemLabel(bot.currentWindow.slots[BET]))
  assertEq(b2, Math.max(100, Math.floor(b1 / 2)), `÷2 (${money(b1)} → ${money(b2)})`)
  win = await expectWindow(bot, () => clickSlot(bot, BAR))
  assert(/Casino/.test(windowTitle(win)), 'back → casino hub')
  await closeWindow()
}

function wheelMaxNow(v) {
  return Math.min(v.maxBet, Math.floor(v.cover / 50), v.room)
}

async function spinTests() {
  let wins = 0
  for (let n = 1; n <= 5; n++) {
    const pk = await spin(`spin #${n}`, n === 1
      ? async () => {
        const win = await expectWindow(bot, () => bot.chat(`/wheel ${dollars(STAKE)}`))
        assert(/Wheel of Fortune/.test(windowTitle(win)), '/wheel <bet> opens the wheel and spins')
      }
      : () => clickSlot(bot, SPIN), STAKE)
    if (pk.payout > 0) wins++
  }
  console.log(`[E2E] INFO 5 spins, ${wins} with a payout`)
  assert(/Last spin/.test(itemLabel(bot.currentWindow?.slots?.[29])), 'history shows the last spin')
}

async function closeMidSpin() {
  const before = await info()
  await clickSlot(bot, SPIN)
  const pk = await checkDecided('close mid-spin', before, STAKE)
  const note = waitForMessage(bot, /\(window closed\)/, 6000).then(() => true, () => false)
  await clickSlot(bot, CLOSE)
  const t0 = Date.now()
  const after = await waitSettled(3000)
  assert(Date.now() - t0 < 2500, 'closing settles at once')
  checkMoney('close mid-spin', before, after, STAKE, pk.payout)
  assert(await note, 'result message names "window closed"')
  await sleep(6000) // past the casino watchdog's 5 s
  const later = await info()
  assertEq([later.balance, later.casinoIn, later.casinoOut, later.rounds], [after.balance, after.casinoIn, after.casinoOut, after.rounds],
    'close mid-spin: nothing paid twice')
}

async function disconnectMidSpin() {
  const before = await info()
  await expectWindow(bot, () => bot.chat(`/wheel ${dollars(STAKE)}`))
  const pk = await checkDecided('disconnect mid-spin', before, STAKE)
  bot.quit()
  await sleep(1500)
  const after = await waitSettled(5000)
  checkMoney('disconnect mid-spin', before, after, STAKE, pk.payout)
  bot = await connectBot(B)
  const note = await waitForMessage(bot, /\(window closed\)/, 8000, { includePast: true }).then(() => true, () => false)
  assert(note, 'result message delivered after rejoining')
  await sleep(500)
  const later = await info()
  assertEq([later.balance, later.casinoIn, later.casinoOut, later.rounds], [after.balance, after.casinoIn, after.casinoOut, after.rounds],
    'disconnect mid-spin: nothing paid twice')
}

async function guardTests() {
  const funded = (await info()).balance
  await rcon(`eco set ${B} 0.05`)
  ecoAdjust += 5 - funded
  let before = await info()
  let msg = waitForMessage(bot, /Not enough money/, 6000).then(() => true, () => false)
  await expectWindow(bot, () => bot.chat(`/wheel ${dollars(STAKE)}`))
  assert(await msg, 'not enough money → message')
  let after = await info()
  assertEq([after.balance, after.open, after.casinoIn], [before.balance, 'none', before.casinoIn], 'not enough money: nothing collected')
  assert(/SPIN/.test(itemLabel(bot.currentWindow?.slots?.[SPIN])), 'wheel idle after the refusal')
  await closeWindow()
  await rcon(`eco set ${B} ${dollars(funded)}`)
  ecoAdjust += funded - 5

  // casino max bet (2 % of the Treasury): the casino decides, the wheel reports it
  before = await info()
  const over = before.maxBet + 1
  let text = waitForMessage(bot, new RegExp(escapeRe(money(before.maxBet))), 6000).then((m) => m.input, () => null)
  await expectWindow(bot, () => bot.chat(`/wheel ${dollars(over)}`))
  text = await text
  assert(!!text, `bet above the casino max (${money(over)}) → message names ${money(before.maxBet)}`)
  if (text) {
    assert(new RegExp(`Maximum bet: ${escapeRe(money(before.maxBet))} \\(at most [\\d.]+ ?% of the Treasury\\)`).test(text),
      `max bet refusal (${text})`)
  }
  after = await info()
  assertEq([after.balance, after.open, after.casinoIn], [before.balance, 'none', before.casinoIn], 'above max bet: nothing collected')
  await closeWindow()

  before = await info()
  text = waitForMessage(bot, /Minimum bet: \$[\d,.]+/, 6000).then((m) => m.input, () => null)
  await expectWindow(bot, () => bot.chat('/wheel 0.01'))
  text = await text
  assert(!!text && /Minimum bet: \$[\d,.]+/.test(text), `min bet refusal (${text})`)
  after = await info()
  assertEq([after.balance, after.open, after.casinoIn], [before.balance, 'none', before.casinoIn], 'below min bet: nothing collected')
  await closeWindow()

  // jackpot coverage: below the casino max bet but 50× not covered
  const wheelMax = wheelMaxNow(before)
  if (wheelMax < before.maxBet && wheelMax < before.room) {
    msg = waitForMessage(bot, /can't cover a possible win/, 6000).then(() => true, () => false)
    await expectWindow(bot, () => bot.chat(`/wheel ${dollars(wheelMax + 1)}`))
    assert(await msg, `bet ${money(wheelMax + 1)} (50× not covered) → coverage message`)
    after = await info()
    assertEq([after.balance, after.open, after.casinoIn], [before.balance, 'none', before.casinoIn], 'not covered: nothing collected')
    const maxLabel = cents(itemLabel(bot.currentWindow?.slots?.[BET]))
    assert(maxLabel <= wheelMax, `bet display clamped to the wheel max (${money(maxLabel)})`)
    await closeWindow()
  } else {
    console.log(`[E2E] INFO coverage check skipped (wheel max ${wheelMax} = casino max ${before.maxBet} or loss room ${before.room})`)
  }
}

async function helpTest() {
  const lines = []
  const collect = (m) => lines.push(stripColors(m))
  bot.on('messagestr', collect)
  bot.chat('/wheel help')
  await sleep(1500)
  bot.off('messagestr', collect)
  const all = lines.join(' | ')
  assert(/Wheel of Fortune/.test(all) && /Odds: .*0× 55\.3 %/.test(all) && /50× 0\.2 %/.test(all), `/wheel help (${all.slice(0, 300)})`)
}

async function restartTests() {
  const { restartServer } = await import('../tools/server-restart.mjs')
  for (const kill of [false, true]) {
    const label = kill ? 'kill -9 mid-spin' : 'stop mid-spin'
    const before = await info()
    await expectWindow(bot, () => bot.chat(`/wheel ${dollars(STAKE)}`))
    const pk = await checkDecided(label, before, STAKE)
    let midSpin = false
    if (kill) {
      await rcon('save-all flush') // round + bet on disk
      midSpin = (await peek()).open === 'wheel' // still spinning → the saved state has the round
    }
    await closeRcon()
    await restartServer({ kill })
    bot = await connectBot(B)
    await sleep(1000)
    const after = await info()
    checkMoney(label, before, after, STAKE, pk.payout)
    if (midSpin) {
      // the reason comes from the casino core's start-up recovery
      const recovered = await waitForMessage(bot, /\(server restart\)/i, 8000, { includePast: true }).then(() => true, () => false)
      assert(recovered, `${label}: the saved round was settled by the start-up recovery`)
    } else if (kill) {
      console.log(`[E2E] INFO ${label}: the wheel had stopped before the crash – recovery path not exercised this time`)
    }
  }
}
