// Blackjack: selftests, /casino hub entry, /blackjack table (hit, stand, double, split), rounds with money
// conservation over RCON (balance = before - stakes + payout, treasury books exactly that under "casino"), max
// bet, help, settlement on close and disconnect (open hands stand, exactly once). With E2E_SERVER_DIR (private
// server copy) also an open round across "stop" and across kill -9 after save-all: settled exactly once at the
// stored value after the restart.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, windowTitle,
  expectWindow, treasuryFlows, dollars, kv,
} from './lib.mjs'

const BOT = 'BotBjA'
const STAKE = 1000 // $10 in cents
/** CasinoMenus.SLOT_BLACKJACK */
const HUB_SLOT = 11
const S = { dealer: 0, help: 9, status: 13, hand1: 18, hand2: 27, hit: 37, stand: 39, double: 41, split: 43, back: 45, stake: 48, deal: 49, close: 53 }
const RESTART = !!process.env.E2E_SERVER_DIR && !process.env.E2E_NO_RESTART

async function info(name = BOT) {
  const line = await rcon(`smpeco casino info ${name}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`smpeco casino info ${name}: ${line}`)
  const f = (await treasuryFlows()).flows.casino ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

async function peek(name = BOT) {
  return kv(await rcon(`smpeco casino blackjack ${name}`))
}

/** Settle line: "Blackjack: Won $x (×m, bet $y)", "Lost (bet $y)" or "Round cancelled, bet $x refunded." */
const SETTLED = /Blackjack: (?:Won \$[\d,]+\.\d\d \(×[\d.]+, bet \$[\d,]+\.\d\d\)|Lost \(bet \$[\d,]+\.\d\d\)|Round cancelled, bet \$[\d,]+\.\d\d refunded\.)/
/**
 * Stake (including double and split) and payout come from the statistics; balance and treasury entries must
 * match them exactly.
 */
function checkRound(label, before, after, { minStake = STAKE } = {}) {
  const staked = after.wagered - before.wagered
  const paid = after.paid - before.paid
  assertEq(after.rounds - before.rounds, 1, `${label}: exactly one round counted`)
  assert(staked >= minStake && staked % STAKE === 0 && staked <= 4 * STAKE, `${label}: stake ${dollars(staked)} (multiple of $10, at most $40)`)
  assert(paid >= 0 && paid <= 8 * STAKE, `${label}: payout ${dollars(paid)} in the possible range`)
  assertEq(after.balance, before.balance - staked + paid, `${label}: balance = before - stake + payout`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [staked, paid],
    `${label}: treasury books stake/payout (reason casino)`)
  assertEq(after.open, 'none', `${label}: no open round`)
  return { staked, paid }
}

const slot = (bot, i) => bot.currentWindow?.slots?.[i]
const label = (bot, i) => itemLabel(slot(bot, i))
const itemName = (bot, i) => slot(bot, i)?.name ?? ''

async function waitTable(bot, timeoutMs = 10000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    if (!bot.currentWindow) return 'closed'
    if (itemName(bot, S.hit) === 'lime_concrete' || itemName(bot, S.stand) === 'red_concrete') return 'move'
    if (itemName(bot, S.deal) === 'lime_concrete') return 'idle'
    await sleep(100)
  }
  throw new Error(`table not ready (status: ${label(bot, S.status)})`)
}

function activeTotal(bot) {
  for (const i of [S.hand1, S.hand2]) {
    const l = label(bot, i)
    const m = l.match(/▶ .*?: (\d+)/)
    if (m) return Number(m[1])
  }
  return null
}

async function playOut(bot, want) {
  const done = { doubled: false, split: false, moves: 0 }
  for (let n = 0; n < 40; n++) {
    const state = await waitTable(bot)
    if (state !== 'move') return done
    if (want.split && !done.split && itemName(bot, S.split) === 'shears') {
      await clickSlot(bot, S.split)
      done.split = true
    } else if (want.double && !done.doubled && itemName(bot, S.double) === 'gold_block') {
      await clickSlot(bot, S.double)
      done.doubled = true
    } else {
      const total = activeTotal(bot)
      assert(total !== null, `active hand readable (${label(bot, S.hand1)} | ${label(bot, S.hand2)})`)
      await clickSlot(bot, total < 17 && itemName(bot, S.hit) === 'lime_concrete' ? S.hit : S.stand)
    }
    done.moves++
    await sleep(350)
  }
  throw new Error('round does not end')
}

async function closeWindow(bot) {
  if (bot.currentWindow) {
    bot.closeWindow(bot.currentWindow)
    await sleep(500)
  }
}

let bot

runE2E(async () => {
  const st = await rcon('smpeco selftest casino-blackjack')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 200)})`)
  if (sm) {
    assert(Number(sm[1]) >= 9, `Blackjack selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `Blackjack-Selftests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  }

  bot = await connectBot(BOT)
  await sleep(1500)
  await rcon(`smpeco casino settle ${BOT}`)
  await rcon(`smpeco casino resetlimit ${BOT}`)
  await rcon(`eco set ${BOT} 5000`)
  await sleep(300)
  const i0 = await info()
  assertEq(i0.balance, 500000, 'starting balance $5000')
  assert(i0.maxBet >= 4 * STAKE, `max bet allows $10 rounds (maxBet=${i0.maxBet})`)

  try {
    await hubTest()
    await helpTest()
    await roundsTest()
    await guardTest()
    await closeTest()
    await disconnectTest()
    if (RESTART) {
      await restartTest(false)
      await restartTest(true)
    } else {
      console.log('[E2E] restart tests skipped (E2E_SERVER_DIR only, without E2E_NO_RESTART)')
    }
  } finally {
    try { await closeWindow(bot) } catch {}
    try {
      await rcon(`smpeco casino settle ${BOT}`)
      await rcon(`smpeco casino resetlimit ${BOT}`)
    } catch {}
  }
}, { timeoutMs: 600000 })

async function hubTest() {
  const win = await expectWindow(bot, () => bot.chat('/casino'))
  assert(/Casino/.test(windowTitle(win)), `casino hub open (${windowTitle(win)})`)
  assertEq(itemLabel(win.slots[HUB_SLOT]), 'Blackjack', `Blackjack entry in the hub (slot ${HUB_SLOT})`)
  const table = await expectWindow(bot, () => clickSlot(bot, HUB_SLOT))
  assert(/Blackjack/.test(windowTitle(table)), `hub opens the table (${windowTitle(table)})`)
  assertEq(itemLabel(table.slots[S.help]), 'How to play', 'help item at the table')
  assert(/Place your bet/.test(label(bot, S.status)), `status: choose a bet (${label(bot, S.status)})`)
  const before = label(bot, S.stake)
  await clickSlot(bot, S.back + 5)
  await sleep(250)
  assert(label(bot, S.stake) !== before, `+$100 changes the bet (${before} to ${label(bot, S.stake)})`)
  await closeWindow(bot)
  assert(!bot.currentWindow, 'table closed')
  assertEq((await info()).open, 'none', 'nothing open without a round')
}

async function helpTest() {
  const got = waitForMessage(bot, /Beat the dealer/, 6000).then(() => true, () => false)
  bot.chat('/blackjack help')
  assert(await got, '/blackjack help explains the rules')
}

async function roundsTest() {
  const want = { double: true, split: true }
  let total = { rounds: 0, staked: 0, paid: 0, doubled: 0, split: 0 }
  for (let n = 0; n < 8; n++) {
    const before = await info()
    if (n === 0) {
      const win = await expectWindow(bot, () => bot.chat(`/blackjack ${dollars(STAKE)}`))
      assert(/Blackjack/.test(windowTitle(win)), 'table open through /blackjack <bet>')
    } else {
      assertEq(await waitTable(bot), 'idle', `round ${n}: table ready`)
    }
    const settledMsg = waitForMessage(bot, SETTLED, 15000).then((m) => m[0], () => null)
    if (n > 0) await clickSlot(bot, S.deal)
    await sleep(300)
    const mid = await info()
    assert(mid.open === 'blackjack:playing' || mid.open === 'blackjack:decided' || mid.open === 'none',
      `round ${n}: round open or already decided (${mid.open})`)
    assertEq(mid.balance, before.balance - STAKE, `round ${n}: stake taken, prize not yet`)
    const done = await playOut(bot, { double: want.double && n >= 1, split: want.split })
    if (done.doubled) want.double = false
    if (done.split) want.split = false
    assertEq(await waitTable(bot), 'idle', `round ${n}: back to idle`)
    await sleep(400)
    const after = await info()
    const r = checkRound(`round ${n}${done.doubled ? ' (Double)' : ''}${done.split ? ' (Split)' : ''}`, before, after)
    if (done.doubled) assert(r.staked >= 2 * STAKE, 'double: doubled stake booked')
    if (done.split) assert(r.staked >= 2 * STAKE, 'split: second stake booked')
    assert(/You win|Dealer wins|Push|BLACKJACK/.test(label(bot, S.status)), `round ${n}: result shown (${label(bot, S.status)})`)
    const line = await settledMsg
    assert(!!line && (r.paid > 0 ? /Won /.test(line) : /Lost /.test(line)), `round ${n}: settle line matches the payout (${line})`)
    total.rounds++
    total.staked += r.staked
    total.paid += r.paid
    if (done.doubled) total.doubled++
    if (done.split) total.split++
  }
  assert(total.doubled === 1, `doubled (${total.doubled})`)
  console.log(`[E2E] Blackjack: ${total.rounds} rounds, stake ${dollars(total.staked)}, payout ${dollars(total.paid)}, split ${total.split}`)
  await closeWindow(bot)
}

async function guardTest() {
  const before = await info()
  const err = waitForMessage(bot, /Maximum bet: \$[\d,]+\.\d\d \(at most [\d.]+% of the Treasury\)/, 6000).then(() => true, () => false)
  const tooMuch = before.maxBet + 100
  bot.chat(`/blackjack ${dollars(tooMuch)}`)
  assert(await err, 'bet above the maximum refused')
  await sleep(300)
  const after = await info()
  assertEq(after.balance, before.balance, 'Refused: nothing charged')
  assertEq(after.open, 'none', 'refused: no round')
  await closeWindow(bot)
}

/**
 * A new hand still waiting for the player. Naturals are decided at once, so they get checked and dealt again.
 * Returns { p: peek of the open round, standValue = value on close, base: state before the deal }.
 */
async function openHand(tag) {
  for (let n = 0; n < 12; n++) {
    const base = await info()
    if (!bot.currentWindow) await expectWindow(bot, () => bot.chat(`/blackjack ${dollars(STAKE)}`))
    else await clickSlot(bot, S.deal)
    const state = await waitTable(bot)
    if (state === 'move') {
      const p = await peek()
      assertEq(p.open, 'blackjack', `${tag}: open blackjack round`)
      assertEq(p.finished, 'false', `${tag}: hand waits for the player`)
      return { p, base }
    }
    await sleep(400)
    checkRound(`${tag}: natural on the deal`, base, await info())
  }
  throw new Error(`${tag}: got no open hand`)
}

async function closeTest() {
  await closeWindow(bot)
  const { p, base } = await openHand('close')
  const msg = waitForMessage(bot, /Blackjack: (?:Won|Lost) .*\(window closed\)/, 6000).then(() => true, () => false)
  await closeWindow(bot)
  await sleep(500)
  const after = await info()
  const r = checkRound('close with open hand', base, after)
  assertEq(r.paid, p.standValue, `close: open hands stand, dealer plays out (${dollars(p.standValue)})`)
  assert(await msg, 'close: settle line with reason "window closed"')
}

async function disconnectTest() {
  const { p, base } = await openHand('Disconnect')
  bot.quit()
  await sleep(2500)
  const after = await info()
  const r = checkRound('disconnect with open hand', base, after)
  assertEq(r.paid, p.standValue, 'disconnect: payout = stand value')
  bot = await connectBot(BOT)
  const msg = await waitForMessage(bot, /Blackjack: (?:Won|Lost) .*\(window closed\)/, 10000, { includePast: true }).then(() => true, () => false)
  assert(msg, 'result delivered after reconnecting (reason "window closed")')
  await sleep(1500)
  assertEq((await info()).balance, after.balance, 'no second payout after the join')
}

async function restartTest(kill) {
  const { stopServer, startServer } = await import('../tools/server-restart.mjs')
  const tag = kill ? 'crash (kill -9)' : 'stop'
  const { p, base } = await openHand(tag)
  if (kill) {
    await rcon('save-all flush') // open round and its charge are on disk now
    await sleep(1500)
  }
  // The bot stays connected with the table open: on stop the server closes the window (settled before saving);
  // on a crash the saved open round stays and is settled on start.
  bot.on('error', () => {})
  const old = bot
  await stopServer({ kill })
  await startServer()
  await sleep(1000)
  const after = await info()
  const r = checkRound(`${tag} with open hand`, base, after)
  assertEq(r.paid, p.standValue, `${tag}: settled exactly once at the stored value after the restart`)
  bot = await connectBot(BOT)
  // Stop: settled when the window closes, message to the old connection or on the next join. Crash: settled on
  // start, message on join.
  const why = kill ? 'server restart' : '(?:window closed|server stop)'
  const re = new RegExp(`Blackjack: (?:Won|Lost) .*\\(${why}\\)`)
  const msg = (old.messages ?? []).some((l) => re.test(l))
    || await waitForMessage(bot, re, 10000, { includePast: true }).then(() => true, () => false)
  assert(msg, `${tag}: settle line (reason ${why}), old: ${(old.messages ?? []).slice(-3).join(' | ')}`)
  await sleep(2500)
  assertEq((await info()).balance, after.balance, `${tag}: no second payout after restart and join`)
}
