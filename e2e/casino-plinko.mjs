// Plinko: selftests, /casino hub entry, GUI (risk buttons, slots, stake display), balls by command, drop button
// and auto x10, closing and disconnecting during the animation (round settled exactly once), max bet, unknown
// risk, help. After each round balance = before - stakes + payouts and the treasury books exactly that under
// "casino", not the raw balance, which NPC orders and buyers change at any time. Every payout must be stake x a
// multiplier of the chosen table. E2E_SERVER_DIR (optional) enables the selftest log check.
import fs from 'node:fs'
import path from 'node:path'
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, treasuryFlows, closeWindow, dollars, kv,
} from './lib.mjs'

const B = 'BotPlinkoA'
const STAKE = 1000 // $10 in cents
const TABLES = {
  low: [560, 210, 110, 100, 40, 100, 110, 210, 560],
  medium: [1300, 300, 130, 70, 30, 70, 130, 300, 1300],
  high: [2800, 400, 140, 30, 20, 30, 140, 400, 2800],
}
const RISK_SLOT = { low: 6, medium: 7, high: 8 }
const S = { help: 0, payouts: 1, auto: 2, chute: 4, bucket0: 36, back: 45, stake: 48, drop: 49, close: 53 }
const HUB_SLOT = 21 // CasinoMenus.SLOT_PLINKO
const CASINO_REASON = 'casino'

async function info(name) {
  const line = await rcon(`smpeco casino info ${name}`)
  const v = kv(line)
  if (v.balance === undefined) throw new Error(`smpeco casino info ${name}: ${line}`)
  const f = (await treasuryFlows()).flows[CASINO_REASON] ?? { income: 0, expense: 0 }
  v.casinoIn = f.income
  v.casinoOut = f.expense
  return v
}

function mult(h) {
  if (h % 100 === 0) return `×${h / 100}`
  if (h % 10 === 0) return `×${Math.floor(h / 100)}.${(h % 100) / 10}`
  return `×${Math.floor(h / 100)}.${String(h % 100).padStart(2, '0')}`
}

async function waitSettled(name, before, n = 1, timeoutMs = 8000) {
  const end = Date.now() + timeoutMs
  let last = null
  while (Date.now() < end) {
    last = await info(name)
    if (last.rounds >= before.rounds + n && last.open === 'none') return last
    await sleep(200)
  }
  throw new Error(`${n} round(s) not settled (before rounds=${before.rounds}, last ${JSON.stringify(last)})`)
}

let totalStaked = 0
let totalPaid = 0

function checkRounds(label, before, after, stake, n, risk) {
  const staked = after.wagered - before.wagered
  const payout = after.paid - before.paid
  assertEq(after.rounds - before.rounds, n, `${label}: ${n} round(s) in the statistics`)
  assertEq(staked, stake * n, `${label}: stake ${dollars(stake * n)}`)
  assertEq(after.balance, before.balance - staked + payout, `${label}: balance = before - stake + payout (${dollars(payout)})`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [staked, payout],
    `${label}: treasury books stake/payout (reason casino)`)
  assertEq(after.open, 'none', `${label}: no open round`)
  if (n === 1 && risk) {
    const hit = TABLES[risk].find((h) => Math.floor((stake * h) / 100) === payout)
    assert(hit !== undefined, `${label}: payout ${dollars(payout)} = stake x multiplier (${hit ? mult(hit) : '?'}, ${risk})`)
  }
  totalStaked += staked
  totalPaid += payout
  return payout
}

async function waitLabel(bot, slot, regex, timeoutMs = 4000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const item = bot.currentWindow?.slots?.[slot]
    if (item && regex.test(itemLabel(item))) return item
    await sleep(100)
  }
  return null
}

function bucketLabels(win) {
  return [...Array(9).keys()].map((k) => itemLabel(win.slots[S.bucket0 + k]))
}

function logSince(offset) {
  const dir = process.env.E2E_SERVER_DIR
  if (!dir) return null
  const file = path.join(dir, 'logs', 'latest.log')
  try {
    const buf = fs.readFileSync(file)
    return buf.subarray(Math.min(offset, buf.length)).toString('utf8')
  } catch {
    return null
  }
}

function logSize() {
  const dir = process.env.E2E_SERVER_DIR
  if (!dir) return 0
  try {
    return fs.statSync(path.join(dir, 'logs', 'latest.log')).size
  } catch {
    return 0
  }
}

runE2E(async () => {
  const offset = logSize()
  const st = await rcon('smpeco selftest casino-plinko')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 200)})`)
  if (sm) {
    assert(Number(sm[1]) >= 10, `Plinko selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `Casino-Selftests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  }
  await sleep(500)
  const log = logSince(offset)
  if (log !== null) {
    const names = [...log.matchAll(/\[SELFTEST\] PASS casino-plinko\/(plinko-[\w-]+)/g)].map((m) => m[1])
    for (const t of ['plinko-tables', 'plinko-rtp', 'plinko-path', 'plinko-engine-rtp', 'plinko-exactly-once',
      'plinko-crash-recovery', 'plinko-guards', 'plinko-void', 'plinko-loss-limit', 'plinko-conservation']) {
      assert(names.includes(t), `selftest ${t} PASS in the log`)
    }
    const rtp = log.match(/PASS casino-plinko\/plinko-rtp: (.*)/)
    if (rtp) console.log(`[E2E] INFO plinko-rtp: ${rtp[1]}`)
  }

  let bot = await connectBot(B)
  await sleep(1500)
  await rcon(`smpeco casino settle ${B}`)
  await rcon(`smpeco casino resetlimit ${B}`)
  await rcon(`eco set ${B} 5000`)
  await sleep(300)
  const i0 = await info(B)
  assertEq(i0.balance, 500000, `starting balance $5000 (${JSON.stringify(i0)})`)
  assert(i0.maxBet >= STAKE && i0.cover >= 28 * STAKE, `treasury allows $10 balls even at high x28 (maxBet=${i0.maxBet}, cover=${i0.cover}, treasury=${i0.treasury})`)

  try {
    let win = await expectWindow(bot, () => bot.chat('/casino'))
    assert(/Casino/.test(windowTitle(win)), `casino menu open (${windowTitle(win)})`)
    assertEq(itemLabel(win.slots[HUB_SLOT]), 'Plinko', `Plinko button in the casino menu on slot ${HUB_SLOT}`)
    const hubButtons = win.slots.slice(0, 45).flatMap((it, i) => (it && itemLabel(it) === 'Plinko' ? [i] : []))
    assertEq(hubButtons, [HUB_SLOT], 'exactly one Plinko button in the casino menu')
    assert(itemLore(win.slots[HUB_SLOT]).some((l) => /\/plinko <bet>/.test(l)), 'hub names /plinko <bet> [low|medium|high]')
    win = await expectWindow(bot, () => clickSlot(bot, HUB_SLOT))
    assert(/Plinko/.test(windowTitle(win)), `Plinko GUI from the hub (${windowTitle(win)})`)
    assertEq(win.slots.slice(0, 54).filter(Boolean).length, 54, '6 rows, all fields filled')
    assertEq(itemLabel(win.slots[S.help]), 'How to play', 'help item')
    assert(itemLore(win.slots[S.help]).some((l) => /\/plinko <bet> \[low\|medium\|high\]/.test(l)), 'help names the command')
    assertEq(itemLabel(win.slots[S.payouts]), 'Payout tables', 'prize table')
    assert(itemLore(win.slots[S.payouts]).some((l) => /RTP 96\.\d\d %/.test(l)), 'prize table names RTP of about 96 %')
    assertEq(itemLabel(win.slots[RISK_SLOT.low]), 'Low risk', 'risk low')
    assertEq(itemLabel(win.slots[RISK_SLOT.medium]), 'Medium risk', 'risk medium')
    assertEq(itemLabel(win.slots[RISK_SLOT.high]), 'High risk', 'risk high')
    for (const risk of ['low', 'high', 'medium']) {
      await clickSlot(bot, RISK_SLOT[risk])
      const want = TABLES[risk].map(mult)
      const end = Date.now() + 3000
      while (Date.now() < end && JSON.stringify(bucketLabels(bot.currentWindow)) !== JSON.stringify(want)) await sleep(100)
      assertEq(bucketLabels(bot.currentWindow), want, `slots show the ${risk} table`)
      assert(itemLore(bot.currentWindow.slots[RISK_SLOT[risk]]).some((l) => /Selected/.test(l)), `${risk} selected`)
    }
    assert(itemLore(bot.currentWindow.slots[S.bucket0 + 4]).some((l) => /Chance: 27\.34 %/.test(l)), 'middle: chance 27.34 %')
    assert(itemLore(bot.currentWindow.slots[S.bucket0]).some((l) => /Chance: 0\.39 %/.test(l)), 'Rand: Chance 0.39 %')
    win = await expectWindow(bot, () => clickSlot(bot, S.back))
    assert(/Casino/.test(windowTitle(win)), `back opens the casino menu (${windowTitle(win)})`)
    await closeWindow(bot)

    let before = await info(B)
    win = await expectWindow(bot, () => bot.chat(`/plinko ${dollars(STAKE)} low`))
    assert(/Plinko/.test(windowTitle(win)), 'command opens Plinko')
    assert(/Bet: \$10\.00/.test(itemLabel(win.slots[S.stake])), `stake display $10 (${itemLabel(win.slots[S.stake])})`)
    let after = await waitSettled(B, before)
    checkRounds('ball by command (low)', before, after, STAKE, 1, 'low')
    assert(!!(await waitLabel(bot, S.drop, /^Drop/)), 'ready again after landing (drop button)')
    assert(itemLore(bot.currentWindow.slots[S.stake]).some((l) => /Last ball:/.test(l)), 'result in the stake display')

    for (const risk of ['low', 'high', 'medium']) {
      if (!itemLore(bot.currentWindow.slots[RISK_SLOT[risk]]).some((l) => /Selected/.test(l))) {
        await clickSlot(bot, RISK_SLOT[risk])
        await sleep(200)
      }
      before = await info(B)
      await clickSlot(bot, S.drop)
      assert(!!(await waitLabel(bot, S.drop, /dropping/, 1500)), `${risk}: ball falling (button locked)`)
      const mid = await info(B)
      assert(mid.open === 'plinko:decided' || mid.rounds === before.rounds + 1, `${risk}: round already decided during the animation (${mid.open})`)
      if (mid.open === 'plinko:decided') assertEq(mid.balance, before.balance - STAKE, `${risk}: stake taken, prize not yet`)
      after = await waitSettled(B, before)
      checkRounds(`drop button (${risk})`, before, after, STAKE, 1, risk)
      await waitLabel(bot, S.drop, /^Drop/)
    }

    await clickSlot(bot, RISK_SLOT.low)
    await sleep(200)
    before = await info(B)
    await clickSlot(bot, S.auto)
    assert(!!(await waitLabel(bot, S.auto, /Stop auto/, 1500)), 'auto running (stop button)')
    after = await waitSettled(B, before, 10, 45000)
    checkRounds('Auto ×10', before, after, STAKE, 10, 'low')
    assert(!!(await waitLabel(bot, S.auto, /Auto ×10/, 4000)), 'auto finished after 10 balls')
    await sleep(500)
    assertEq((await info(B)).rounds, after.rounds, 'exactly 10 auto balls')

    before = await info(B)
    await clickSlot(bot, S.drop)
    await closeWindow(bot)
    after = await waitSettled(B, before, 1, 3000)
    checkRounds('closing during the animation', before, after, STAKE, 1, 'low')
    await sleep(2500) // longer than the animation: nothing may be booked a second time afterwards
    const again = await info(B)
    assertEq([again.balance, again.rounds, again.casinoOut], [after.balance, after.rounds, after.casinoOut], 'no second settlement after closing')

    before = await info(B)
    const tooMuch = before.maxBet + 100
    const errMsg = waitForMessage(bot, /max(imum)? bet/i, 5000).then(() => true, () => false)
    win = await expectWindow(bot, () => bot.chat(`/plinko ${dollars(tooMuch)} high`))
    assert(await errMsg, `error at ${dollars(tooMuch)} above the max bet`)
    await sleep(500)
    after = await info(B)
    assertEq([after.balance, after.rounds, after.casinoIn, after.casinoOut, after.open],
      [before.balance, before.rounds, before.casinoIn, before.casinoOut, 'none'], 'refused: nothing booked, no round')
    await closeWindow(bot)

    const unk = waitForMessage(bot, /Unknown risk level/, 4000).then(() => true, () => false)
    bot.chat('/plinko 10 extreme')
    assert(await unk, 'unknown risk reported')
    const help = waitForMessage(bot, /Plinko: drop a ball .*\/plinko <bet> \[low\|medium\|high\]/, 4000).then(() => true, () => false)
    bot.chat('/plinko help')
    assert(await help, '/plinko help: help line')
    await sleep(300)
    assertEq((await info(B)).rounds, before.rounds, 'no round from errors or help')

    before = await info(B)
    await expectWindow(bot, () => bot.chat(`/plinko ${dollars(STAKE)} high`))
    bot.quit()
    await sleep(2500)
    after = await waitSettled(B, before, 1, 4000)
    checkRounds('disconnect during the animation', before, after, STAKE, 1, 'high')
    bot = await connectBot(B)
    await sleep(2500)
    const rejoin = await info(B)
    assertEq([rejoin.balance, rejoin.rounds], [after.balance, after.rounds], 'no second payout after the join')

    const end = await info(B)
    assertEq(end.balance, i0.balance - totalStaked + totalPaid, `total: balance = start - ${dollars(totalStaked)} + ${dollars(totalPaid)}`)
    assertEq([end.casinoIn - i0.casinoIn, end.casinoOut - i0.casinoOut], [totalStaked, totalPaid], 'total: treasury books all stakes and payouts')
    console.log(`[E2E] INFO ${end.rounds - i0.rounds} balls, stake ${dollars(totalStaked)}, payout ${dollars(totalPaid)}`)
    globalThis.plinkoBot = bot
  } finally {
    const b = globalThis.plinkoBot ?? bot
    try { await closeWindow(b) } catch {}
    await rcon(`smpeco casino settle ${B}`)
    await rcon(`smpeco casino resetlimit ${B}`)
  }
}, { timeoutMs: 300000 })
