// Casino game "Horse Race": selftests via RCON, /horserace GUI (pick a horse, set the bet, bet, track view), bets by
// command, several races (started via RCON instead of waiting 30 s), payout = bet × odds for the winner only, guards
// (max bet, one bet per race, no bets while running), result after a disconnect, and exactly-once settlement across a
// server stop (bet refunded) and a crash during a running race (decided payout paid once). Money conservation: every
// change of the bots' balances is matched by the casino ledger of the treasury (reason "casino"). Shared with the casino:
// race losses and open bets count in the /casino daily limit, and "smpeco casino resetlimit" clears the race's day too.
// Env: E2E_PORT / E2E_RCON_PORT / E2E_SERVER_DIR (private server copy), E2E_NO_RESTART=1 skips the stop/crash part.
import {
  connectBot, rcon, closeRcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel,
  itemLore, windowTitle, expectWindow, stripColors, treasuryFlows, cents, closeWindow, dollars, kv,
} from './lib.mjs'
import { stopServer, startServer, killServer } from '../tools/server-restart.mjs'

/** CasinoMenus.SLOT_HORSERACE */
const HUB_SLOT = 24
const B1 = 'horseBot1'
const B2 = 'horseBot2'
const REASON = 'casino'
const HORSE_SLOTS = [10, 11, 12, 14, 15, 16]
const TAG_SLOTS = [19, 20, 21, 23, 24, 25]
const NAMES = ['Leather Lightning', 'Copper Comet', 'Iron Duke', 'Golden Gallop', 'Diamond Dash', 'Netherite Nightmare']

async function status() {
  const line = await rcon('smpeco casino horserace status')
  const v = kv(line)
  if (v.race === undefined) throw new Error(`horserace status: ${line}`)
  v.oddsList = String(v.odds).split(',').map(Number)
  return v
}

async function info(name) {
  const line = await rcon(`smpeco casino horserace info ${name}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`horserace info ${name}: ${line}`)
  return v
}

async function money() {
  const f = (await treasuryFlows()).flows[REASON] ?? { income: 0, expense: 0 }
  return { b1: (await info(B1)).balance, b2: (await info(B2)).balance, income: f.income, expense: f.expense }
}

/** Every cent the bots lost or won went through the casino ledger (and nothing else changed their balances). */
function checkConservation(label, before, after, staked, paid) {
  assertEq([after.income - before.income, after.expense - before.expense], [staked, paid], `${label}: ledger books stakes/payouts`)
  assertEq((after.b1 + after.b2) - (before.b1 + before.b2), paid - staked, `${label}: bots' balances change by payouts − stakes`)
}

const payoutOf = (stake, odds) => Math.floor((stake * odds) / 100)

function resultPromise(bot, timeoutMs = 45000, opts = {}) {
  const re = /Horse Race #(\d+): (?:.*You win (\$[\d,]+\.\d\d)|.*Your bet on .* (lost)|race cancelled.* your bet of (\$[\d,]+\.\d\d) was refunded)/
  return waitForMessage(bot, re, timeoutMs, opts).then((m) => ({
    race: Number(m[1]), win: !!m[2], payout: m[2] ? cents(m[2]) : m[4] ? cents(m[4]) : 0, refund: !!m[4], text: stripColors(m.input),
  }))
}

async function waitFor(fn, timeoutMs = 10000, stepMs = 200) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const v = await fn()
    if (v) return v
    await sleep(stepMs)
  }
  return null
}

// a race from an earlier run may still be running
async function waitBetting() {
  const s = await waitFor(async () => {
    const v = await status()
    return v.state === 'betting' ? v : null
  }, 45000, 500)
  if (!s) throw new Error('horse race never back in betting state')
  return s
}

function chatExpect(bot, cmd, regex, timeoutMs = 5000) {
  const p = waitForMessage(bot, regex, timeoutMs).then((m) => m, () => null)
  bot.chat(cmd)
  return p
}

let bot1
let bot2

runE2E(async () => {
  const st = await rcon('smpeco selftest casino-horserace')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary (${st.slice(0, 200)})`)
  if (sm) {
    assert(Number(sm[1]) >= 12, `horse race selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `horse race selftests failed=0 ${Number(sm[2]) ? st.slice(0, 2000) : ''}`)
  }

  bot1 = await connectBot(B1)
  bot2 = await connectBot(B2)
  await sleep(1500)
  await rcon('smpeco casino horserace settle')
  await waitBetting()
  for (const b of [B1, B2]) {
    await rcon(`smpeco casino resetlimit ${b}`)
    await rcon(`eco set ${b} 5000`)
  }
  await sleep(300)
  const i0 = await info(B1)
  assertEq(i0.balance, 500000, 'start balance $5000')
  assert(i0.maxBet >= 10000 && i0.maxBet <= Math.floor(i0.treasury * 0.02), `max bet ≤ 2 % of the treasury (maxBet=${i0.maxBet}, treasury=${i0.treasury})`)

  try {
    await hubEntry()
    await guiRace()
    await commandRaces()
    await sharedWithCasino()
    await disconnectRace()
    if (process.env.E2E_NO_RESTART !== '1') {
      await stopRefund()
      await crashDuringRace()
    }
  } finally {
    try { await closeWindow(bot1) } catch {}
  }
}, { timeoutMs: 600000 })

async function hubEntry() {
  const hub = await expectWindow(bot1, () => bot1.chat('/casino'))
  assert(/Casino/.test(windowTitle(hub)), `casino hub open (${windowTitle(hub)})`)
  assertEq(itemLabel(hub.slots[HUB_SLOT]), 'Horse Race', 'hub entry for the horse race')
  assert(itemLore(hub.slots[HUB_SLOT]).some((l) => /Max bet/.test(l)), 'hub entry shows the max bet')
  const win = await expectWindow(bot1, () => clickSlot(bot1, HUB_SLOT))
  assert(/Horse Race/.test(windowTitle(win)), `hub entry opens the race (${windowTitle(win)})`)
  await closeWindow(bot1)
}

async function guiRace() {
  const s0 = await waitBetting()
  const m0 = await money()
  const races0 = (await info(B1)).races
  const win = await expectWindow(bot1, () => bot1.chat('/horserace'))
  assert(/Horse Race/.test(windowTitle(win)), `GUI open (${windowTitle(win)})`)
  for (let h = 0; h < 6; h++) {
    const item = win.slots[HORSE_SLOTS[h]]
    assert(new RegExp(`#${h + 1} ${NAMES[h]}`).test(itemLabel(item)), `horse ${h + 1}: ${itemLabel(item)}`)
    const odds = itemLore(item).find((l) => /^Odds:/.test(l)) ?? ''
    assertEq(odds, `Odds: ×${(s0.oddsList[h] / 100).toFixed(2)}`, `horse ${h + 1}: displayed odds = card odds`)
  }
  assertEq(itemLabel(win.slots[0]), 'How it works', 'help item')
  assert(itemLore(win.slots[0]).some((l) => /after the first bet/.test(l)), 'help explains the start')
  assert(new RegExp(`Race #${s0.race}`).test(itemLabel(win.slots[4])), `status shows the race (${itemLabel(win.slots[4])})`)
  await clickSlot(bot1, HORSE_SLOTS[2])
  assert(/Picked/.test(itemLabel(bot1.currentWindow.slots[TAG_SLOTS[2]])), 'horse #3 picked')
  // bet: down to the minimum ($1), +$100 gives $101, /2 gives $50.50, /2 gives $25.25 (same slot twice: wait
  // longer than Gui.SAME_SLOT_DEBOUNCE_MS, or the second click counts as a double click)
  await clickSlot(bot1, 47, 1)
  await sleep(400)
  await clickSlot(bot1, 47, 1)
  assert(/Bet: \$1\.00/.test(itemLabel(bot1.currentWindow.slots[48])), `bet at the minimum (${itemLabel(bot1.currentWindow.slots[48])})`)
  await clickSlot(bot1, 50)
  await clickSlot(bot1, 46)
  await sleep(400)
  await clickSlot(bot1, 46)
  const stake1 = 2525
  assert(/Bet: \$25\.25/.test(itemLabel(bot1.currentWindow.slots[48])), `bet $25.25 (${itemLabel(bot1.currentWindow.slots[48])})`)
  assert(/Bet \$25\.25 on #3/.test(itemLabel(bot1.currentWindow.slots[49])), `action button (${itemLabel(bot1.currentWindow.slots[49])})`)
  const placed = waitForMessage(bot1, /Bet placed: \$25\.25 on #3 Iron Duke/, 5000).then(() => true, () => false)
  await clickSlot(bot1, 49)
  assert(await placed, 'bet placed message')
  const i1 = await info(B1)
  assertEq([i1.bet, i1.stake, i1.payout], [3, stake1, -1], 'bet stored (horse 3, $25.25, no outcome yet)')
  assertEq(i1.balance, m0.b1 - stake1, 'stake taken right away')
  const s1 = await status()
  // 30 s after the first bet, or later if the last race started less than 2 minutes ago (global cadence)
  assert(s1.closesIn > 25000 && s1.closesIn <= 120000, `first bet started the countdown (closesIn=${s1.closesIn})`)
  await sleep(300)
  assert(/Bet placed/.test(itemLabel(bot1.currentWindow.slots[49])), 'GUI shows the bet')
  assert(/Your bet: \$25\.25/.test(itemLabel(bot1.currentWindow.slots[31])), 'your bet panel')
  assert(await chatExpect(bot1, '/horserace bet 2 10', /already have a bet/), 'second bet on the same race rejected')
  const big = i1.maxBet + 100
  assert(await chatExpect(bot2, `/horserace bet 5 ${dollars(big)}`, /Maximum bet/), `bet above the max (${dollars(big)}) rejected`)
  assertEq((await info(B2)).balance, m0.b2, 'rejected bet: nothing taken')
  const stake2 = 1500
  assert(await chatExpect(bot2, '/horserace bet 5 15', /Bet placed: \$15\.00 on #5 Diamond Dash/), 'B2 bet by command')
  const s2 = await status()
  assertEq(s2.bets, 2, 'two bets on the same race')
  assertEq(s2.closesIn <= s1.closesIn, true, 'second bet does not move the start')
  const r1 = resultPromise(bot1)
  const r2 = resultPromise(bot2)
  const start = await rcon('smpeco casino horserace start')
  assert(/start ok/.test(start), `race started (${start.trim()})`)
  const run = await status()
  assertEq(run.state, 'running', 'running')
  assert(run.winner >= 1 && run.winner <= 6, `winner decided at the start (${run.winner})`)
  assert(await chatExpect(bot2, '/horserace bet 1 5', /Betting is closed/), 'no bets while the race runs')
  const gate = await waitFor(() => /Leather Lightning/.test(itemLabel(bot1.currentWindow?.slots?.[0])) ? true : null, 4000, 100)
  assert(!!gate, `GUI switched to the track (${itemLabel(bot1.currentWindow?.slots?.[0])})`)
  assert(/FINISH/.test(itemLabel(bot1.currentWindow.slots[8])), 'finish line in column 9')
  const [a, b] = await Promise.all([r1, r2])
  assertEq([a.race, b.race], [run.race, run.race], 'both results for this race')
  assertEq(a.payout, run.winner === 3 ? payoutOf(stake1, run.oddsList[2]) : 0, `B1: payout ${dollars(a.payout)} (winner #${run.winner})`)
  assertEq(b.payout, run.winner === 5 ? payoutOf(stake2, run.oddsList[4]) : 0, `B2: payout ${dollars(b.payout)}`)
  const winnerSlot = (run.winner - 1) * 9 + 8
  const first = await waitFor(() => /1st/.test(itemLabel(bot1.currentWindow?.slots?.[winnerSlot])) ? true : null, 8000, 200)
  assert(!!first, `winner shown 1st at the finish (${itemLabel(bot1.currentWindow?.slots?.[winnerSlot])})`)
  await sleep(500)
  const m1 = await money()
  checkConservation('race 1', m0, m1, stake1 + stake2, a.payout + b.payout)
  const after = await info(B1)
  assertEq([after.bet, after.races], [0, races0 + 1], 'B1: bet settled, +1 race in the statistics')
  const next = await waitFor(() => /How it works/.test(itemLabel(bot1.currentWindow?.slots?.[0])) ? true : null, 15000, 300)
  assert(!!next, 'GUI back to betting on the next card')
  assert(new RegExp(`Race #${run.race + 1}`).test(itemLabel(bot1.currentWindow.slots[4])), 'next race number')
  await closeWindow(bot1)
}

async function commandRaces() {
  const m0 = await money()
  let staked = 0
  let paid = 0
  let wins = 0
  for (let n = 0; n < 3; n++) {
    const s = await waitBetting()
    const fav = s.oddsList.indexOf(Math.min(...s.oddsList)) + 1
    const out = s.oddsList.indexOf(Math.max(...s.oddsList)) + 1
    const st1 = 1000 + n * 100
    const st2 = 500
    assert(await chatExpect(bot1, `/horserace bet ${fav} ${dollars(st1)}`, /Bet placed/), `race ${s.race}: B1 on the favourite #${fav}`)
    assert(await chatExpect(bot2, `/horserace bet ${out} ${dollars(st2)}`, /Bet placed/), `race ${s.race}: B2 on the outsider #${out}`)
    const r1 = resultPromise(bot1)
    const r2 = resultPromise(bot2)
    await rcon('smpeco casino horserace start')
    const run = await status()
    const [a, b] = await Promise.all([r1, r2])
    assertEq(a.payout, run.winner === fav ? payoutOf(st1, s.oddsList[fav - 1]) : 0, `race ${s.race}: B1 payout (winner #${run.winner})`)
    assertEq(b.payout, run.winner === out ? payoutOf(st2, s.oddsList[out - 1]) : 0, `race ${s.race}: B2 payout`)
    staked += st1 + st2
    paid += a.payout + b.payout
    if (a.win || b.win) wins++
    await sleep(300)
  }
  const m1 = await money()
  checkConservation('races 2–4', m0, m1, staked, paid)
  console.log(`[E2E] INFO races 2–4: staked ${dollars(staked)}, paid ${dollars(paid)}, races with a winner among the bots: ${wins}`)
}

/** One daily loss limit with the casino games: race losses and the open bet show in the casino's numbers; reset clears both. */
async function sharedWithCasino() {
  const casino = async (name) => {
    const line = await rcon(`smpeco casino info ${name}`)
    const v = kv(line)
    if (v.room === undefined) throw new Error(`casino info ${name}: ${line}`)
    return v
  }
  await waitBetting()
  const h0 = await info(B1)
  const c0 = await casino(B1)
  assertEq(c0.netToday, h0.otherUsed + h0.netToday, 'casino daily loss includes the race')
  assertEq(c0.room, h0.room, 'casino and race show the same daily room')
  const stake = 1200
  assert(c0.room > stake, `room for the test bet (${c0.room})`)
  assert(await chatExpect(bot1, `/horserace bet 1 ${dollars(stake)}`, /Bet placed/), 'B1 bets (open bet)')
  const c1 = await casino(B1)
  assertEq(c1.room, c0.room - stake, 'open race bet reduces the casino room')
  assertEq((await info(B1)).room, c1.room, 'race room = casino room with the open bet')
  const r1 = resultPromise(bot1)
  await rcon('smpeco casino horserace start')
  const a = await r1
  await sleep(300)
  const c2 = await casino(B1)
  assertEq(c2.netToday, c0.netToday + stake - a.payout, `settled race in the casino daily loss (payout ${dollars(a.payout)})`)
  await rcon(`smpeco casino resetlimit ${B1}`)
  const h3 = await info(B1)
  const c3 = await casino(B1)
  assertEq([h3.netToday, c3.netToday], [0, 0], 'resetlimit clears the race day and the casino day')
  assertEq(c3.room, c3.limit, 'full room after the reset')
}

/** B2 bets and leaves; the race is paid anyway and the result is delivered on the next join, once. */
async function disconnectRace() {
  const s = await waitBetting()
  const m0 = await money()
  const stake = 700
  assert(await chatExpect(bot2, `/horserace bet 2 ${dollars(stake)}`, /Bet placed/), 'B2 bets, then leaves')
  bot2.quit()
  await sleep(1500)
  await rcon('smpeco casino horserace start')
  const run = await status()
  const settled = await waitFor(async () => ((await status()).lastRace === run.race ? true : null), 45000, 500)
  assert(!!settled, 'race settled while B2 is offline')
  const expected = run.winner === 2 ? payoutOf(stake, s.oddsList[1]) : 0
  const m1 = await money()
  checkConservation('offline race', m0, m1, stake, expected)
  bot2 = await connectBot(B2)
  const r = await resultPromise(bot2, 10000, { includePast: true }).catch(() => null)
  assert(r && r.race === run.race && r.payout === expected, `result delivered after the join (${r?.text})`)
  await sleep(2500)
  assertEq((await info(B2)).balance, m1.b2, 'no second payout after the join')
}

/** Server stop with a bet on a card that has not started: refunded exactly once. */
async function stopRefund() {
  await waitBetting()
  const m0 = await money()
  const stake = 1000
  assert(await chatExpect(bot1, `/horserace bet 4 ${dollars(stake)}`, /Bet placed/), 'B1 bets, then the server stops')
  const refund = resultPromise(bot1, 60000)
  await closeRcon()
  await stopServer()
  const r = await refund.catch(() => null)
  assert(r && r.refund && r.payout === stake, `refund announced on stop (${r?.text})`)
  await startServer()
  bot1 = await connectBot(B1)
  bot2 = await connectBot(B2)
  await sleep(2000)
  const m1 = await money()
  checkConservation('stop with an open bet', m0, m1, stake, stake)
  assertEq((await info(B1)).bet, 0, 'no bet left after the restart')
}

/** Crash (kill -9) during a running race that was saved: after the restart the stored outcome is paid once. */
async function crashDuringRace() {
  const s = await waitBetting()
  const m0 = await money()
  const stake = 2000
  const stake2 = 1000
  const fav = s.oddsList.indexOf(Math.min(...s.oddsList)) + 1
  const pick1 = fav === 6 ? 5 : 6
  assert(await chatExpect(bot1, `/horserace bet ${pick1} ${dollars(stake)}`, /Bet placed/), 'B1 bets before the crash')
  assert(await chatExpect(bot2, `/horserace bet ${fav} ${dollars(stake2)}`, /Bet placed/), 'B2 bets on the favourite before the crash')
  await rcon('smpeco casino horserace start')
  const run = await status()
  assertEq(run.state, 'running', 'race running')
  await rcon('save-all')
  await sleep(1500)
  const pre = await status()
  assertEq(pre.state, 'running', `still running when killed (settleIn=${pre.settleIn})`)
  await closeRcon()
  await killServer()
  try { bot1.quit() } catch {}
  try { bot2.quit() } catch {}
  await startServer()
  const expected = run.winner === pick1 ? payoutOf(stake, s.oddsList[pick1 - 1]) : 0
  const expected2 = run.winner === fav ? payoutOf(stake2, s.oddsList[fav - 1]) : 0
  bot1 = await connectBot(B1)
  bot2 = await connectBot(B2)
  const r = await resultPromise(bot1, 15000, { includePast: true }).catch(() => null)
  assert(r && r.race === run.race && r.payout === expected && /server restart/.test(r.text), `B1: stored outcome paid after the crash (${r?.text})`)
  const r2 = await resultPromise(bot2, 15000, { includePast: true }).catch(() => null)
  assert(r2 && r2.race === run.race && r2.payout === expected2 && /server restart/.test(r2.text), `B2: stored outcome paid after the crash (${r2?.text})`)
  await sleep(2000)
  const m1 = await money()
  checkConservation('crash during a race', m0, m1, stake + stake2, expected + expected2)
  const i = await info(B1)
  assertEq(i.bet, 0, 'no open bet after the restart')
  const again = await money()
  assertEq([again.b1, again.b2], [m1.b1, m1.b2], 'paid exactly once')
}
