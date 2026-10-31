// Casino: selftests, /casino menu, slots, coinflip against the house (with double or nothing), mines,
// higher/lower, max bet, daily limit, payout on disconnect, PvP double-or-nothing offer, statistics GUI. After
// each round balance = before - stake + payout and the treasury books exactly that under "casino", not the raw
// balance, which NPC orders and buyers change at any time.
import {
  connectBot, rcon, assert, assertEq, sleep, runE2E, waitForMessage, clickSlot, itemLabel, itemLore,
  windowTitle, expectWindow, treasuryFlows, cents, closeWindow, dollars, kv,
} from './lib.mjs'

const B1 = 'casinoBot1'
const B2 = 'casinoBot2'
const STAKE = 1000 // $10 in cents

/** Stakes = income, payouts = expense, since opening. */
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

async function peek(name) {
  const line = await rcon(`smpeco casino peek ${name}`)
  const v = kv(line)
  const list = (s) => (typeof s === 'string' && s.startsWith('[') ? s.slice(1, -1).split(',').filter(Boolean).map(Number) : [])
  v.minesList = list(v.mines)
  v.revealedList = list(v.revealed)
  return v
}

async function waitSlot(bot, slot, regex, timeoutMs = 6000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    const item = bot.currentWindow?.slots?.[slot]
    if (item && regex.test(itemLabel(item))) return item
    await sleep(100)
  }
  return null
}

function resultPromise(bot, game, timeoutMs = 15000) {
  const re = new RegExp(`${game}: (?:Won (\\$[\\d,]+\\.\\d\\d)|(Lost)|Round cancelled, bet (\\$[\\d,]+\\.\\d\\d) refunded)`)
  return waitForMessage(bot, re, timeoutMs).then((m) => ({ win: !!m[1], payout: m[1] ? cents(m[1]) : m[3] ? cents(m[3]) : 0, refund: !!m[3] }))
}

/**
 * Checks the treasury through its casino entries. With the balance this says the same as "player + treasury
 * constant", but independently of background entries of other reasons.
 */
function checkBalances(label, before, after, stake, payout) {
  assertEq(after.balance, before.balance - stake + payout, `${label}: balance = before - stake + payout`)
  assertEq([after.casinoIn - before.casinoIn, after.casinoOut - before.casinoOut], [stake, payout],
    `${label}: treasury books stake/payout (reason casino)`)
  assertEq(after.open, 'none', `${label}: no open round`)
}

function nCr(n, k) {
  let r = 1n
  for (let i = 1n; i <= BigInt(k); i++) r = (r * (BigInt(n) - BigInt(k) + i)) / i
  return r
}

/** Mines payout as on the server (house edge in basis points, default 400 = 4 %). */
function minesPayout(stake, bombs, k, edgeBp = 400) {
  return Number((BigInt(stake) * BigInt(10000 - edgeBp) * nCr(25, k)) / (10000n * nCr(25 - bombs, k)))
}

const minesSlot = (t) => Math.floor(t / 5) * 9 + 2 + (t % 5)

runE2E(async () => {
  const st = await rcon('smpeco selftest casino')
  const sm = st.match(/SUMMARY passed=(\d+) failed=(\d+)/)
  assert(!!sm, `selftest summary present (${st.slice(0, 200)})`)
  if (sm) {
    assert(Number(sm[1]) >= 15, `Casino selftests ran (passed=${sm[1]})`)
    assertEq(Number(sm[2]), 0, `Casino-Selftests failed=0 ${Number(sm[2]) ? st.slice(0, 1500) : ''}`)
  }

  const bot = await connectBot(B1)
  await sleep(1500)
  await rcon(`smpeco casino settle ${B1}`)
  await rcon(`smpeco casino resetlimit ${B1}`)
  await rcon(`eco set ${B1} 5000`)
  await sleep(300)
  let i0 = await info(B1)
  assertEq(i0.balance, 500000, `starting balance $5000 (${JSON.stringify(i0)})`)
  assert(i0.maxBet >= 2 * STAKE, `max bet allows $10 rounds (maxBet=${i0.maxBet}, treasury=${i0.treasury})`)
  assert(i0.maxBet <= Math.floor(i0.treasury * 0.02), 'max bet at most 2 % of the treasury')

  try {
    await menuTests(bot)
    await slotsTests(bot)
    await coinflipTests(bot)
    await minesTests(bot)
    await hiloTests(bot)
    await guardTests(bot)
    await disconnectTest(bot)
    await doubleOrNothingTest()
    await statsTest()
  } finally {
    const b = globalThis.casinoBot ?? bot
    try { await closeWindow(b) } catch {}
    await rcon(`smpeco casino settle ${B1}`)
    await rcon(`smpeco casino settle ${B2}`)
    await rcon(`smpeco casino resetlimit ${B1}`)
    await rcon(`smpeco casino resetlimit ${B2}`)
  }
}, { timeoutMs: 300000 })

async function menuTests(bot) {
  const win = await expectWindow(bot, () => bot.chat('/casino'))
  assert(/Casino/.test(windowTitle(win)), `casino menu open (${windowTitle(win)})`)
  // a fixed slot per game (CasinoMenus.SLOT_*), each game exactly once
  const HUB = { 10: 'Coinflip', 11: 'Blackjack', 12: 'Slots', 13: 'Roulette', 14: 'Mines', 15: 'Dice', 16: 'Higher/Lower',
    20: 'Crash', 21: 'Plinko', 22: 'Wheel of Fortune', 23: 'Scratch Cards', 24: 'Horse Race' }
  for (const [slot, label] of Object.entries(HUB)) assertEq(itemLabel(win.slots[Number(slot)]), label, `Hub Slot ${slot}: ${label}`)
  const labels = win.slots.slice(0, 45).map((it) => (it ? itemLabel(it) : null)).filter((l) => Object.values(HUB).includes(l))
  assertEq(labels.length, Object.keys(HUB).length, 'each game exactly once in the hub')
  assert(itemLore(win.slots[28]).some((l) => /Max bet/.test(l)), 'limits with max bet')
  assert(itemLore(win.slots[32]).some((l) => /Loss limit today/.test(l)), 'daily limit display')
}

async function slotsTests(bot) {
  let win = await expectWindow(bot, () => clickSlot(bot, 12))
  assert(/Slots/.test(windowTitle(win)), `slots GUI open (${windowTitle(win)})`)
  assertEq(itemLabel(win.slots[4]), 'Paytable', 'prize table visible')
  assert(itemLore(win.slots[4]).some((l) => /Diamond.*×25/.test(l)), 'prize table names 3x Diamond x25')
  await closeWindow(bot)
  for (let round = 1; round <= 3; round++) {
    const before = await info(B1)
    const result = resultPromise(bot, 'Slots')
    win = round === 1
      ? await expectWindow(bot, () => bot.chat(`/slots ${dollars(STAKE)}`))
      : (await clickSlot(bot, 40), bot.currentWindow)
    if (round === 1) assert(/Bet: \$10\.00/.test(itemLabel(win.slots[39])), `stake display $10 (${itemLabel(win.slots[39])})`)
    const r = await result
    await sleep(300)
    const after = await info(B1)
    checkBalances(`Slots #${round} (${r.win ? 'win ' + dollars(r.payout) : 'lost'})`, before, after, STAKE, r.payout)
    assertEq(after.rounds, before.rounds + 1, `slots #${round}: stats +1 round`)
    await sleep(400)
  }
  assert(bot.currentWindow && /Slots/.test(windowTitle(bot.currentWindow)), 'slots GUI stays open for more rounds')
  await clickSlot(bot, 41)
  await sleep(200)
  assert(/Bet: \$110\.00/.test(itemLabel(bot.currentWindow.slots[39])), `+$100 → $110 (${itemLabel(bot.currentWindow.slots[39])})`)
  await clickSlot(bot, 37)
  await sleep(200)
  assert(/Bet: \$55\.00/.test(itemLabel(bot.currentWindow.slots[39])), `÷2 → $55 (${itemLabel(bot.currentWindow.slots[39])})`)
  await clickSlot(bot, 44)
  await sleep(400)
  assert(!bot.currentWindow, 'slots GUI closed')
}

async function coinflipTests(bot) {
  let wins = 0
  let losses = 0
  let doubled = false
  let cashedOut = false
  for (let n = 0; n < 30 && (wins === 0 || losses === 0 || !doubled || !cashedOut); n++) {
    const before = await info(B1)
    const lost = resultPromise(bot, 'Coinflip', 8000).catch(() => null)
    if (!bot.currentWindow) {
      const win = await expectWindow(bot, () => bot.chat(`/coinflip house ${dollars(STAKE)}`))
      assert(n > 0 || /Coinflip/.test(windowTitle(win)), `coinflip GUI open (${windowTitle(win)})`)
    } else {
      await clickSlot(bot, 31)
    }
    const choice = await waitSlot(bot, 20, /Cash Out/, 6000)
    if (!choice) {
      const r = await lost
      assert(r && !r.win, `coinflip #${n}: loss reported`)
      const after = await info(B1)
      checkBalances(`coinflip #${n} lost`, before, after, STAKE, 0)
      losses++
      await sleep(400)
      continue
    }
    wins++
    assertEq(cents(itemLabel(choice)), 2 * STAKE, `coinflip #${n}: pot = stake x 2`)
    const mid = await info(B1)
    assertEq(mid.open, 'coinflip:playing', 'round waits for a decision')
    assertEq(mid.balance, before.balance - STAKE, 'stake taken, prize not yet')
    let pot = 2 * STAKE
    if (!doubled) {
      doubled = true
      assert(/Double or Nothing/.test(itemLabel(bot.currentWindow.slots[24])), '"Double or nothing" button')
      const res2 = resultPromise(bot, 'Coinflip', 8000).catch(() => null)
      await clickSlot(bot, 24)
      const again = await waitSlot(bot, 20, new RegExp(`Cash Out \\$${dollars(4 * STAKE).replace('.', '\\.')}`), 6000)
      if (!again) {
        const r = await res2
        assert(r && !r.win, 'double or nothing: loss reported')
        const after = await info(B1)
        checkBalances('double or nothing lost', before, after, STAKE, 0)
        await sleep(400)
        continue
      }
      pot = 4 * STAKE
      assert(true, 'double or nothing won (pot x4)')
    }
    const paid = resultPromise(bot, 'Coinflip', 6000)
    await clickSlot(bot, 20)
    const r = await paid
    assertEq(r.payout, pot, `cash out pays the pot (${dollars(pot)})`)
    const after = await info(B1)
    checkBalances(`coinflip #${n} cashed out`, before, after, STAKE, pot)
    cashedOut = true
    await sleep(400)
  }
  assert(wins > 0 && losses > 0, `coinflip: wins (${wins}) and losses (${losses}) seen`)
  assert(doubled, '"double or nothing" tested')
  assert(cashedOut, 'coinflip pot cashed out')
  await closeWindow(bot)
}

async function minesTests(bot) {
  const bombs = 3
  let before = await info(B1)
  let win = await expectWindow(bot, () => bot.chat(`/mines ${dollars(STAKE)} ${bombs}`))
  assert(/Mines/.test(windowTitle(win)), `mines GUI open (${windowTitle(win)})`)
  let p = await peek(B1)
  assertEq(p.open, 'mines', 'mines round open')
  assertEq(p.minesList.length, bombs, 'bombs placed and stored before the first click')
  const safe = [...Array(25).keys()].filter((t) => !p.minesList.includes(t))
  await clickSlot(bot, minesSlot(safe[0]))
  assert(await waitSlot(bot, minesSlot(safe[0]), /Safe/, 3000), 'field 1 revealed safely')
  await sleep(350)
  await clickSlot(bot, minesSlot(safe[1]))
  assert(await waitSlot(bot, minesSlot(safe[1]), /Safe/, 3000), 'field 2 revealed safely')
  const expected = minesPayout(STAKE, bombs, 2)
  const cash = await waitSlot(bot, 26, /Cash Out/, 2000)
  assertEq(cash ? cents(itemLabel(cash)) : -1, expected, `cash-out button shows ${dollars(expected)}`)
  const paid = resultPromise(bot, 'Mines')
  await clickSlot(bot, 26)
  const r = await paid
  assertEq(r.payout, expected, 'mines: payout after 2 fields')
  let after = await info(B1)
  checkBalances('mines cashed out', before, after, STAKE, expected)
  await sleep(300)
  assert(/Bomb|BOOM/.test(itemLabel(bot.currentWindow.slots[minesSlot(p.minesList[0])])), 'bombs shown after the round')

  before = await info(B1)
  await clickSlot(bot, 49)
  await sleep(300)
  p = await peek(B1)
  assertEq(p.open, 'mines', 'new mines round')
  const lost = resultPromise(bot, 'Mines')
  await clickSlot(bot, minesSlot(p.minesList[0]))
  const r2 = await lost
  assert(!r2.win, 'bomb = lost')
  assert(await waitSlot(bot, minesSlot(p.minesList[0]), /BOOM/, 2000), 'BOOM field shown')
  after = await info(B1)
  checkBalances('mines bomb', before, after, STAKE, 0)

  before = await info(B1)
  await clickSlot(bot, 49)
  await sleep(300)
  const refund = resultPromise(bot, 'Mines')
  await closeWindow(bot)
  const r3 = await refund
  assert(r3.refund && r3.payout === STAKE, 'close without a field: stake back')
  after = await info(B1)
  checkBalances('mines cancelled', before, after, STAKE, STAKE)
}

async function hiloTests(bot) {
  let sawWin = false
  let sawLoss = false
  for (let n = 0; n < 12 && !(sawWin && sawLoss); n++) {
    const before = await info(B1)
    if (!bot.currentWindow) {
      const win = await expectWindow(bot, () => bot.chat(`/hilo ${dollars(STAKE)}`))
      assert(n > 0 || /Higher\/Lower/.test(windowTitle(win)), `hi-lo GUI open (${windowTitle(win)})`)
    } else {
      await clickSlot(bot, 40)
      await sleep(300)
    }
    const p = await peek(B1)
    assertEq(p.open, 'hilo', `hi-lo #${n}: round open`)
    const higher = p.card <= 8
    const slot = higher ? 24 : 20
    assert(/Higher|Lower/.test(itemLabel(bot.currentWindow.slots[slot])), `Hi-Lo #${n}: guess button (${itemLabel(bot.currentWindow.slots[slot])})`)
    const lost = resultPromise(bot, 'Higher/Lower', 6000).catch(() => null)
    await clickSlot(bot, slot)
    const cash = await waitSlot(bot, 31, /^Cash Out \$/, 3000)
    if (!cash) {
      const r = await lost
      assert(r && !r.win, `hi-lo #${n}: loss reported`)
      const after = await info(B1)
      checkBalances(`hi-lo #${n} lost`, before, after, STAKE, 0)
      sawLoss = true
      continue
    }
    const shown = cents(itemLabel(cash))
    const paid = resultPromise(bot, 'Higher/Lower', 6000)
    await clickSlot(bot, 31)
    const r = await paid
    assertEq(r.payout, shown, `hi-lo #${n}: payout as shown (${dollars(shown)})`)
    const after = await info(B1)
    checkBalances(`hi-lo #${n} cashed out`, before, after, STAKE, shown)
    sawWin = true
    await sleep(300)
  }
  assert(sawWin || sawLoss, 'hi-lo played')
  await closeWindow(bot)
}

async function guardTests(bot) {
  const i = await info(B1)
  const tooMuch = i.maxBet + 100
  const err = waitForMessage(bot, /Maximum bet/, 6000).then(() => true, () => false)
  await expectWindow(bot, () => bot.chat(`/slots ${dollars(tooMuch)}`))
  assert(await err, `bet above the maximum (${dollars(tooMuch)}) refused`)
  const i2 = await info(B1)
  assertEq(i2.balance, i.balance, 'refused bet: nothing charged')
  assertEq(i2.open, 'none', 'refused bet: no round')
  await closeWindow(bot)

  // personal daily limit, lowering applies at once: at limit 0 only today's winnings may be gambled
  const set = waitForMessage(bot, /Daily loss limit is now \$0\.00/, 5000).then(() => true, () => false)
  bot.chat('/casino limit 0')
  assert(await set, 'personal limit set to $0')
  const lim = await info(B1)
  assertEq(lim.limit, 0, 'limit applies immediately')
  const over = lim.room + 100
  const limited = waitForMessage(bot, /Daily loss limit reached/, 6000).then(() => true, () => false)
  await expectWindow(bot, () => bot.chat(`/slots ${dollars(over)}`))
  assert(await limited, `bet above the daily limit refused (room ${dollars(lim.room)})`)
  assertEq((await info(B1)).balance, lim.balance, 'refused: nothing charged')
  await closeWindow(bot)
  const raise = waitForMessage(bot, /takes effect tomorrow/, 5000).then(() => true, () => false)
  bot.chat('/casino limit off')
  assert(await raise, 'lifting applies only from tomorrow')
  const still = await info(B1)
  assertEq(still.limit, 0, 'limit stays at $0 today')
  await rcon(`smpeco casino resetlimit ${B1}`)
  const reset = await info(B1)
  assert(reset.limit > 0, 'admin reset lifts the limit')
}

async function disconnectTest(bot) {
  const before = await info(B1)
  await expectWindow(bot, () => bot.chat(`/mines ${dollars(STAKE)} 3`))
  const p = await peek(B1)
  const safe = [...Array(25).keys()].find((t) => !p.minesList.includes(t))
  await clickSlot(bot, minesSlot(safe))
  assert(await waitSlot(bot, minesSlot(safe), /Safe/, 3000), 'field safe, round running')
  // leave without cashing out: the round is paid exactly once on leaving
  bot.quit()
  await sleep(2500)
  const after = await info(B1)
  const expected = minesPayout(STAKE, 3, 1)
  checkBalances('disconnect with open mines round', before, after, STAKE, expected)
  const back = await connectBot(B1)
  globalThis.casinoBot = back
  const msg = await waitForMessage(back, /Mines: Won \$[\d,.]+ .*window closed/, 10000, { includePast: true }).then(() => true, () => false)
  assert(msg, 'result delivered after reconnecting')
  await sleep(1500)
  const again = await info(B1)
  assertEq(again.balance, after.balance, 'no second payout after the join')
}

async function doubleOrNothingTest() {
  const bot2 = await connectBot(B2)
  await sleep(1500)
  await rcon(`smpeco casino settle ${B2}`)
  await rcon(`smpeco casino resetlimit ${B2}`)
  await rcon(`eco set ${B2} 1000`)
  await sleep(300)
  // Services.DoubleOrNothing, as minigames call it after a PvP coinflip win
  const offered = waitForMessage(bot2, /Double or Nothing\?/, 6000).then(() => true, () => false)
  let out = await rcon(`smpeco casino offer ${B2} 10`)
  const m = out.match(/offer=(\S+)/)
  assert(!!m, `offer created (${out.trim()})`)
  assert(await offered, 'offer in chat')
  if (m) {
    const before = await info(B2)
    const result = resultPromise(bot2, 'Coinflip', 10000).catch(() => null)
    const win = await expectWindow(bot2, () => bot2.chat(`/casino double ${m[1]}`))
    assert(/Coinflip/.test(windowTitle(win)), 'accepting opens the coinflip')
    const choice = await waitSlot(bot2, 20, /Cash Out/, 6000)
    let payout = 0
    if (choice) {
      const paid = resultPromise(bot2, 'Coinflip', 6000)
      await clickSlot(bot2, 20)
      payout = (await paid).payout
      assertEq(payout, 2000, 'PvP double or nothing: $20 paid')
    } else {
      const r = await result
      assert(r && !r.win, 'PvP double or nothing: loss reported')
    }
    const after = await info(B2)
    checkBalances('PvP double or nothing', before, after, 1000, payout)
    if (bot2.currentWindow) bot2.closeWindow(bot2.currentWindow)
    await sleep(400)
    const gone = waitForMessage(bot2, /no longer available/, 5000).then(() => true, () => false)
    bot2.chat(`/casino double ${m[1]}`)
    assert(await gone, 'offer can be accepted only once')
  }
  await rcon(`eco set ${B2} 1000`)
  out = await rcon(`smpeco casino offer ${B2} 5`)
  const m2 = out.match(/offer=(\S+)/)
  if (m2) {
    const declined = waitForMessage(bot2, /Offer declined/, 5000).then(() => true, () => false)
    bot2.chat(`/casino double ${m2[1]} no`)
    assert(await declined, 'decline offer (double … no)')
  } else {
    assert(false, `second offer (${out.trim()})`)
  }
  const big = await rcon(`smpeco casino offer ${B2} 1000000`)
  assert(/offered=false/.test(big), `offer above the max bet refused (${big.trim()})`)
}

async function statsTest() {
  const bot = globalThis.casinoBot
  const i = await info(B1)
  const win = await expectWindow(bot, () => bot.chat('/casino stats'))
  assert(/Stats/.test(windowTitle(win)), `statistics GUI open (${windowTitle(win)})`)
  const lore = itemLore(win.slots[10]).join(' | ')
  assert(new RegExp(`Rounds: ${i.rounds.toLocaleString('en-US')}\\b`).test(lore), `statistics show ${i.rounds} rounds (${lore})`)
  assert(/Slots/.test(itemLabel(win.slots[13])), 'slots stats')
  await closeWindow(bot)
}
