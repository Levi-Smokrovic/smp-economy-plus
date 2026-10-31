// Dice: selftests, /casino menu entry, /dice GUI (direction, target, presets, bet), /dice <bet> <over|under>
// <number> with an immediate roll, help, limits, casino messages with the label "Dice", big-win
// announcement. For each round the result is already decided in casino.json during the animation (smpeco casino
// peek); afterwards balance = before - stake + payout, the treasury books exactly that under "casino", and the
// payout matches the shown roll (99 / chance x 0.97, rounded down).
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, treasuryFlows, closeRcon, closeWindow, dollars, kv,
} from './lib.mjs'

/** CasinoMenus.SLOT_DICE */
const HUB_SLOT = 15
const BOT = 'BotDiceA'
const STAKE = 1000 // $10 in cents
const EDGE_BP = 300 // default casino_dice.json edgePercent 3
const S = { help: 4, mode: 10, minus10: 11, minus1: 12, target: 13, plus1: 14, plus10: 15, odds: 16, die: 31, result: 33, back: 45, stake: 48, roll: 49, close: 53 }
const PRESETS = { 37: 90, 38: 75, 39: 50, 40: 33, 41: 20, 42: 10, 43: 2 }

async function info() {
  const line = await rcon(`smpeco casino info ${BOT}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`smpeco casino info ${BOT}: ${line}`)
  const f = (await treasuryFlows()).flows.casino ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

const peek = async () => kv(await rcon(`smpeco casino peek ${BOT}`))

/** Payout as on the server: floor(stake * 99 * (10000 - edge) / (chance * 10000)). */
function payout(stake, chance, edgeBp = EDGE_BP) {
  return Number((BigInt(stake) * 99n * BigInt(10000 - edgeBp)) / (BigInt(chance) * 10000n))
}

const chanceOf = (over, target) => (over ? 100 - target : target - 1)

function betOf(win) {
  const m = itemLabel(win.slots[S.target]).match(/Roll (OVER|UNDER) (\d+)/)
  return m ? { over: m[1] === 'OVER', target: Number(m[2]) } : null
}

/**
 * The server can lag a few ticks under load; the pause after keeps the next click out of the 150 ms button
 * debounce.
 */
async function clickBet(bot, slot, expected, label, timeoutMs = 6000) {
  await clickSlot(bot, slot)
  const end = Date.now() + timeoutMs
  let bet = betOf(bot.currentWindow)
  while (Date.now() < end && JSON.stringify(bet) !== JSON.stringify(expected)) {
    await sleep(100)
    bet = betOf(bot.currentWindow)
  }
  assertEq(bet, expected, label)
  await sleep(250)
  return bet
}

async function waitLanded(bot, timeoutMs = 8000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const label = itemLabel(bot.currentWindow?.slots?.[S.die])
    const m = label.match(/^(\d+) – (WIN!|no luck)/)
    if (m) return { roll: Number(m[1]), win: m[2] === 'WIN!' }
    await sleep(100)
  }
  throw new Error(`die not landed after ${timeoutMs} ms (${itemLabel(bot.currentWindow?.slots?.[S.die])})`)
}

async function waitOpenNone(timeoutMs = 8000) {
  const end = Date.now() + timeoutMs
  let i
  while (Date.now() < end) {
    i = await info()
    if (i.open === 'none') return i
    await sleep(200)
  }
  return i
}

const totals = { stake: 0, payout: 0, rounds: 0, wins: 0, decidedSeen: 0 }

/**
 * Polls RCON tick by tick after {@code trigger}: the result must be decided and stored while the die still
 * rolls. A server catching up after a lag spike can finish the 28 tick animation before RCON gets a turn;
 * returns null then.
 */
async function startAndPeek(before, trigger) {
  await trigger()
  const end = Date.now() + 6000
  while (Date.now() < end) {
    const p = await peek()
    if (p.open === 'dice') return p
    if (kv(await rcon(`smpeco casino info ${BOT}`)).rounds > before.rounds) return null
    await sleep(20)
  }
  return null
}

// click without clickSlot's pause, since the animation lasts only 28 ticks
const clickNow = (bot, slot) => () => { bot.clickWindow(slot, 0, 0).catch(() => {}) }

async function checkRound(label, bot, before, bet, stake, p) {
  if (p) {
    assertEq([p.open, p.state, p.stake], ['dice', 'decided', stake], `${label}: result decided and stored during the animation`)
    totals.decidedSeen++
  } else {
    console.log(`[E2E] INFO ${label}: animation over before RCON got a turn (server catching up), final state only`)
  }
  const after = await waitOpenNone()
  await sleep(300)
  const landed = await waitLanded(bot)
  const chance = chanceOf(bet.over, bet.target)
  const wins = bet.over ? landed.roll > bet.target : landed.roll < bet.target
  assertEq(landed.win, wins, `${label}: roll ${landed.roll} with ${bet.over ? 'over' : 'under'} ${bet.target} → ${wins ? 'win' : 'loss'}`)
  const want = wins ? payout(stake, chance) : 0
  if (p) assertEq(p.payout, want, `${label}: stored payout = ${dollars(want)} (chance ${chance} %)`)
  assertEq(after.open, 'none', `${label}: round settled`)
  assertEq(after.balance, before.balance - stake + want, `${label}: balance = before - stake + payout`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [stake, want], `${label}: treasury books stake/payout (reason casino)`)
  assertEq(after.rounds, before.rounds + 1, `${label}: stats +1 round`)
  totals.stake += stake
  totals.payout += want
  totals.rounds++
  if (wins) totals.wins++
  return { landed, want, after }
}

/**
 * For the abort tests: roll until RCON catches it mid-animation, at most 3 tries. A roll that lands too fast
 * settles normally. Returns { before, p } or null.
 */
async function rollCaught(label, trigger) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const before = await info()
    const p = await startAndPeek(before, trigger)
    if (p) return { before, p }
    console.log(`[E2E] INFO ${label}: roll already landed, trying again`)
    await waitOpenNone()
    await sleep(500)
  }
  return null
}

const RESULT_RE = /Dice: (?:Won \$([\d,]+\.\d\d) \(×[\d.]+, bet \$([\d,]+\.\d\d)\)|Lost \(bet \$([\d,]+\.\d\d)\))(?: \((.+)\))?/
const centsOf = (s) => Math.round(Number(s.replace(/,/g, '')) * 100)
function resultOf(m) {
  return { win: !!m[1], payout: m[1] ? centsOf(m[1]) : 0, stake: centsOf(m[2] ?? m[3]), why: m[4] ?? null }
}

let treasuryTopUp = 0 // $, private server copy only, withdrawn at the end

runE2E(async () => {
  const st = await rcon('smpeco selftest casino-dice')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 200)})`)
  if (sm) {
    assert(Number(sm[1]) >= 8, `Dice selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `Dice-Selftests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  }

  let bot = await connectBot(BOT)
  await sleep(1000)
  await rcon(`smpeco casino settle ${BOT}`)
  await rcon(`smpeco casino resetlimit ${BOT}`)
  await rcon(`eco set ${BOT} 5000`)
  await sleep(300)
  const start = await info()
  assertEq(start.balance, 500000, 'starting balance $5000')
  assert(start.maxBet >= 2 * STAKE, `max bet allows $10 rolls (maxBet=${start.maxBet}, treasury=${start.treasury})`)
  assert(start.maxBet <= Math.floor(start.treasury * 0.02), 'max bet at most 2 % of the treasury')

  try {
    let win = await expectWindow(bot, () => bot.chat('/casino'))
    assert(/Casino/.test(windowTitle(win)), `casino menu open (${windowTitle(win)})`)
    assertEq(itemLabel(win.slots[HUB_SLOT]), 'Dice', `Dice in the casino menu (slot ${HUB_SLOT})`)
    assert(itemLore(win.slots[HUB_SLOT]).some((l) => /\/dice <bet> <over\|under> <number>/.test(l)), 'menu entry names the command')
    win = await expectWindow(bot, () => clickSlot(bot, HUB_SLOT))
    assertEq(windowTitle(win), 'Dice', 'dice table opened from the menu')
    assertEq(itemLabel(win.slots[S.help]), 'How to play', 'help book')
    assert(itemLore(win.slots[S.help]).some((l) => /Payout = 99 ÷ win chance × 0\.97/.test(l)), 'help names the formula')
    assert(/^ROLL \(\$/.test(itemLabel(win.slots[S.roll])), `ROLL button (${itemLabel(win.slots[S.roll])})`)
    win = await expectWindow(bot, () => clickSlot(bot, S.back))
    assert(/Casino/.test(windowTitle(win)), 'back to the casino menu')
    await closeWindow(bot)

    const help = waitForMessage(bot, /Usage: \/dice <bet> <over\|under> <number>/, 5000)
    bot.chat('/dice help')
    assert(!!(await help), '/dice help shows the help line')
    const bad = waitForMessage(bot, /Roll over: pick a number from 5 to 99/, 5000)
    bot.chat(`/dice ${dollars(STAKE)} over 100`)
    assert(!!(await bad), 'over 100 refused (range named)')
    await sleep(300)
    const tooBig = start.maxBet + 100
    const maxMsg = waitForMessage(bot, /Maximum bet: \$[\d,]+\.\d\d \(at most [\d.]+ ?% of the Treasury\)/, 6000).then(() => true, () => false)
    const big = await expectWindow(bot, () => bot.chat(`/dice ${dollars(tooBig)} over 50`))
    assertEq(windowTitle(big), 'Dice', 'table opens even with too high a bet')
    await sleep(500)
    let i = await info()
    assertEq([i.balance, i.open], [start.balance, 'none'], 'bet above the maximum: nothing charged, no round')
    assert(await maxMsg, 'message "Maximum bet: … of the Treasury"')
    await closeWindow(bot)

    let before = await info()
    const line3 = waitForMessage(bot, RESULT_RE, 15000)
    let p = await startAndPeek(before, async () => { win = await expectWindow(bot, () => bot.chat(`/dice ${dollars(STAKE)} over 50`)) })
    let bet = betOf(win)
    assertEq(bet, { over: true, target: 50 }, 'bet taken from the command')
    const r3 = await checkRound('command over 50', bot, before, bet, STAKE, p)
    const res3 = resultOf(await line3)
    assertEq([res3.win, res3.payout, res3.stake, res3.why], [r3.want > 0, r3.want, STAKE, null], 'chat: "Dice: Won …/Lost …" matches the roll')
    assert(/You (won|lost)/.test(itemLabel(bot.currentWindow.slots[S.result])), `result display (${itemLabel(bot.currentWindow.slots[S.result])})`)
    assert(/Stake: \$10\.00/.test(itemLabel(bot.currentWindow.slots[S.stake])), 'bet stays $10')

    await clickBet(bot, 41, { over: true, target: 80 }, 'preset 20 % gives over 80')
    assert(/×4\.80/.test(itemLore(bot.currentWindow.slots[S.odds]).join(' ')), `multiplier ×4.80 (${itemLore(bot.currentWindow.slots[S.odds]).join(' | ')})`)
    await clickBet(bot, S.mode, { over: false, target: 21 }, 'direction gives under 21 (same chance)')
    await clickBet(bot, S.plus10, { over: false, target: 31 }, '+10 → under 31')
    bet = await clickBet(bot, S.minus1, { over: false, target: 30 }, '−1 → under 30')

    // null = current bet (under 30), otherwise a preset slot; from roll 4 on "over" again
    const plan = [null, 39, 37, 42, 43, 38]
    for (let n = 0; n < plan.length; n++) {
      if (n === 3) bet = await clickBet(bot, S.mode, { over: true, target: 10 }, 'direction gives over 10 (90 %)')
      if (plan[n] != null) {
        const over = n >= 3
        bet = await clickBet(bot, plan[n], { over, target: over ? 100 - PRESETS[plan[n]] : PRESETS[plan[n]] + 1 }, `roll ${n + 1}: preset ${PRESETS[plan[n]]} %`)
      }
      before = await info()
      p = await startAndPeek(before, clickNow(bot, S.roll))
      await checkRound(`roll ${n + 1} (${bet.over ? 'over' : 'under'} ${bet.target})`, bot, before, bet, STAKE, p)
      await sleep(350)
    }
    assert(totals.decidedSeen >= 3, `result seen stored during the animation in ${totals.decidedSeen}/${totals.rounds} rolls`)
    assert(/Last rolls/.test(itemLabel(bot.currentWindow.slots[29])) && itemLore(bot.currentWindow.slots[29]).length >= 6, 'history shows the last rolls')

    i = await info()
    assertEq(i.balance - start.balance, totals.payout - totals.stake, `bot: sum of all rounds (${totals.rounds} rolls, ${totals.wins} wins)`)
    assertEq((i.casinoIn - start.casinoIn) - (i.casinoOut - start.casinoOut), totals.stake - totals.payout, 'treasury (reason casino): stakes - payouts')
    assertEq((i.balance - start.balance) + ((i.casinoIn - start.casinoIn) - (i.casinoOut - start.casinoOut)), 0, 'money conservation: bot + treasury constant')

    let caught = await rollCaught('close', clickNow(bot, S.roll))
    assert(!!caught, 'close: roll caught mid-animation')
    if (!caught) throw new Error('no roll mid-animation')
    ;({ before, p } = caught)
    assertEq([p.open, p.state], ['dice', 'decided'], 'close: roll running, result fixed')
    const line7 = waitForMessage(bot, RESULT_RE, 8000)
    bot.closeWindow(bot.currentWindow)
    i = await waitOpenNone()
    const res7 = resultOf(await line7)
    assertEq([res7.payout, res7.stake, res7.why], [p.payout, STAKE, 'window closed'], 'chat: result with reason "window closed"')
    assertEq(i.balance, before.balance - STAKE + p.payout, 'close during the animation: paid exactly once')
    assertEq([i.casinoIn - before.casinoIn, i.casinoOut - before.casinoOut], [STAKE, p.payout], 'close: treasury books once')
    assertEq(i.rounds, before.rounds + 1, 'close: one round')
    await sleep(1500)
    const i2 = await info()
    assertEq([i2.balance, i2.rounds], [i.balance, i.rounds], 'no second settlement later (watchdog)')

    caught = await rollCaught('Disconnect', () => bot.chat(`/dice ${dollars(STAKE)} under 50`))
    assert(!!caught, 'disconnect: roll caught mid-animation')
    if (!caught) throw new Error('no roll mid-animation')
    ;({ before, p } = caught)
    assertEq([p.open, p.state], ['dice', 'decided'], 'disconnect: roll running, result fixed')
    bot.quit()
    await sleep(1500)
    i = await waitOpenNone()
    assertEq(i.balance, before.balance - STAKE + p.payout, 'disconnect during the animation: paid exactly once')
    assertEq([i.casinoIn - before.casinoIn, i.casinoOut - before.casinoOut], [STAKE, p.payout], 'disconnect: treasury books once')
    bot = await connectBot(BOT)
    await sleep(2500)
    const i3 = await info()
    assertEq([i3.balance, i3.open, i3.pending, i3.rounds], [i.balance, 'none', 0, i.rounds], 'after the rejoin: nothing twice, nothing open')
    const res8 = resultOf(await waitForMessage(bot, RESULT_RE, 8000, { includePast: true }))
    assertEq([res8.payout, res8.stake], [p.payout, STAKE], 'result of the disconnect roll delivered after the rejoin ("Dice:")')

    // Big-win announcement to everyone with the "announcements" setting. Over 50 with a $2,720 stake gives at
    // least $2,500 profit (announceMinProfit default); up to 6 tries at 50 %. Needs a treasury of at least
    // $136,000 (max bet 2 %), topped up temporarily on a private server copy (E2E_SERVER_DIR).
    const BIG = 272000
    let g = await info()
    if (g.maxBet < BIG && process.env.E2E_SERVER_DIR) {
      treasuryTopUp = Math.ceil((BIG * 50 - g.treasury) / 100) + 1000 // whole dollars
      await rcon(`eco treasury deposit ${treasuryTopUp}`)
      await sleep(300)
      g = await info()
    }
    if (g.maxBet >= BIG && g.cover >= payout(BIG, 50) && g.room >= 6 * BIG) {
      await rcon(`eco set ${BOT} ${Math.ceil((g.balance + 6 * BIG) / 100)}`)
      await sleep(300)
      let announced = null
      let tries = 0
      while (tries < 6 && !announced) {
        tries++
        const line = waitForMessage(bot, RESULT_RE, 15000)
        const ann = waitForMessage(bot, new RegExp(`${BOT} won \\$([\\d,]+\\.\\d\\d) at Dice \\(×[\\d.]+\\)!`), 15000).then((m) => m, () => null)
        await expectWindow(bot, () => bot.chat(`/dice ${dollars(BIG)} over 50`))
        const r = resultOf(await line)
        assertEq(r.stake, BIG, `big win try ${tries}: stake $${dollars(BIG)}`)
        if (r.win) {
          announced = await ann
          assert(!!announced, `big win ($${dollars(r.payout)}): announcement "${BOT} won … at Dice (×…)!"`)
          if (announced) assertEq(centsOf(announced[1]), r.payout, 'announcement names the payout')
          if (!announced) break
        }
        await waitOpenNone()
        await closeWindow(bot)
        await sleep(400)
      }
      if (!announced) console.log(`[E2E] INFO big win: ${tries} rolls without a win, announcement not checked`)
    } else {
      console.log(`[E2E] INFO big win announcement skipped (maxBet=${g.maxBet}, cover=${g.cover}, room=${g.room})`)
    }

    if (process.env.E2E_SERVER_DIR && process.env.E2E_NO_RESTART !== '1') {
      const { stopServer, startServer } = await import('../tools/server-restart.mjs')
      caught = await rollCaught('stop', () => bot.chat(`/dice ${dollars(STAKE)} over 25`))
      assert(!!caught, 'stop: roll caught mid-animation')
      if (!caught) throw new Error('no roll mid-animation')
      ;({ before, p } = caught)
      assertEq([p.open, p.state], ['dice', 'decided'], 'stop: roll running, result fixed')
      // the settle line arrives before the kick (GUI closed) or after the restart on join (settleAll)
      const stopLine = waitForMessage(bot, RESULT_RE, 30000).then((m) => m, () => null)
      await closeRcon()
      await stopServer({ log: (m) => console.log(`[E2E] ${m}`) })
      await startServer({ log: (m) => console.log(`[E2E] ${m}`) })
      i = await info()
      assertEq(i.open, 'none', 'after the restart: no open round')
      assertEq(i.balance, before.balance - STAKE + p.payout, 'stop during the animation: paid exactly once after the restart')
      assertEq([i.casinoIn - before.casinoIn, i.casinoOut - before.casinoOut], [STAKE, p.payout], 'stop: treasury books once')
      assertEq(i.rounds, before.rounds + 1, 'stop: one round in the statistics')
      bot = await connectBot(BOT)
      await sleep(2500)
      const i4 = await info()
      assertEq([i4.balance, i4.open, i4.pending], [i.balance, 'none', 0], 'after the rejoin: nothing twice')
      const m9 = (await stopLine) ?? (await waitForMessage(bot, RESULT_RE, 8000, { includePast: true }))
      const res9 = resultOf(m9)
      // reason: "window closed" (GUI closed by the kick) or "server stop" (settleAll on stopping)
      assertEq([res9.payout, res9.stake], [p.payout, STAKE], 'result of the stop roll delivered after the rejoin ("Dice: …")')
      assert(['window closed', 'server stop'].includes(res9.why), `reason named (${res9.why})`)
    } else {
      console.log('[E2E] server stop test skipped (E2E_SERVER_DIR of a private copy only)')
    }
  } finally {
    try { await closeWindow(bot) } catch {}
    await rcon(`smpeco casino settle ${BOT}`)
    await rcon(`smpeco casino resetlimit ${BOT}`)
    if (treasuryTopUp > 0) await rcon(`eco treasury withdraw ${treasuryTopUp}`)
  }
}, { timeoutMs: 420000 })
